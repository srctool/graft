// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.GitCommand
import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.core.git.GitUndoRefusedException
import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.graft.core.git.testing.RawGitCommand
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.OperationEntry
import com.srctool.graft.data.git.api.models.OperationKind
import com.srctool.graft.data.git.api.models.OperationOutcome
import com.srctool.graft.data.git.api.models.RefState
import com.srctool.graft.data.git.implementation.repositories.GitOperationLogRepository
import com.srctool.graft.data.git.implementation.repositories.MutationRecorder
import com.srctool.graft.data.git.implementation.source.local.OpLogCodec
import com.srctool.graft.data.git.implementation.source.local.OpLogStore
import com.srctool.graft.data.git.implementation.source.local.UndoReplay
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The op log and Undo against real repositories; the codec and the undo stacks as pure functions. */
@ExtendWith(GitSandboxExtension::class)
class OperationLogRepositoryTest {
    /** What a test builds: the recorder and the log, as the app wires them, with a small cap if asked. */
    private class Rig(sandbox: GitSandbox, runner: GitRunner = sandbox.runner(), cap: Int = OpLogStore.CAP) {
        val store = OpLogStore(cap, slack = 2)
        val recorder = MutationRecorder(runner, store)
        val log = GitOperationLogRepository(recorder, store)
    }

    private fun GitSandbox.commit(repo: Path, message: String, file: String = "f.txt", text: String = message) {
        Files.writeString(repo.resolve(file), text)
        git(repo, "add", file)
        git(repo, "commit", "--quiet", "-m", message)
    }

    private fun head(sandbox: GitSandbox, repo: Path) = sandbox.git(repo, "rev-parse", "HEAD").trim()

    private fun logFile(sandbox: GitSandbox, repo: Path) = repo.resolve(".git/graft/oplog.jsonl")

    /** A commit made as Graft makes it: through the recorder, as [kind]. */
    private suspend fun Rig.commit(
        repo: Path,
        message: String,
        kind: OperationKind = OperationKind.COMMIT,
        vararg extra: String,
    ) = recorder.run(
        RawGitCommand(listOf("commit", "--quiet", "--allow-empty", "-m", message) + extra),
        repo,
        kind,
    )

    private fun entry(
        kind: OperationKind,
        before: String?,
        after: String?,
        branch: String? = "refs/heads/main",
        outcome: OperationOutcome = OperationOutcome.SUCCEEDED,
    ) = OperationEntry(
        time = 1,
        worktree = "main",
        kind = kind,
        argv = listOf("git", "commit"),
        exitCode = 0,
        outcome = outcome,
        stderrTail = "",
        before = RefState(before?.let(::id), branch, null),
        after = RefState(after?.let(::id), branch, null),
    )

    private fun id(n: String) = ObjectId(n.padEnd(40, '0'))

    // --- recording --------------------------------------------------------------------------------------------

    @Test
    fun `a command that worked is logged with its argv and the refs around it`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("f.txt", "1") } }
        val rig = Rig(sandbox)
        val first = head(sandbox, repo)
        rig.commit(repo, "two").getOrThrow()
        val e = rig.log.entries(repo).getOrThrow().single()
        assertEquals(OperationKind.COMMIT, e.kind)
        assertEquals(OperationOutcome.SUCCEEDED, e.outcome)
        assertEquals(0, e.exitCode)
        assertEquals(listOf("git", "commit", "--quiet", "--allow-empty", "-m", "two"), e.argv)
        assertEquals("main", e.worktree)
        assertEquals(first, e.before.head?.hex)
        assertEquals("refs/heads/main", e.before.branch)
        assertEquals(head(sandbox, repo), e.after.head?.hex)
        assertNull(e.after.stash)
    }

    @Test
    fun `a command that failed is logged with its exit code and what git said`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("f.txt", "1") } }
        val rig = Rig(sandbox)
        val before = head(sandbox, repo)
        val result = rig.recorder.run(RawGitCommand(listOf("update-ref", "refs/heads/x", "nonsense")), repo)
        assertIs<Result.Failure>(result)
        val e = rig.log.entries(repo).getOrThrow().single()
        assertEquals(OperationOutcome.FAILED, e.outcome)
        assertTrue((e.exitCode ?: 0) != 0)
        assertTrue(e.stderrTail.isNotBlank())
        assertEquals(before, e.after.head?.hex)
    }

    @Test
    fun `a command that was cancelled is logged, and the cancellation still propagates`(sandbox: GitSandbox) =
        runBlocking {
            val repo = sandbox.repo { commit("one") { file("f.txt", "1") } }
            val real = sandbox.runner()
            val started = CompletableDeferred<Unit>()
            val hanging =
                object : GitRunner by real {
                    override suspend fun <T> run(cmd: GitCommand<T>, cwd: Path, input: ByteArray?): Result<T> {
                        if (cmd.function != "HANG") return real.run(cmd, cwd, input)
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }
            val rig = Rig(sandbox, hanging)
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    rig.recorder.run(RawGitCommand(listOf("gc"), function = "HANG"), repo)
                }
            started.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            val e = rig.log.entries(repo).getOrThrow().single()
            assertEquals(OperationOutcome.CANCELLED, e.outcome)
            assertNull(e.exitCode)
        }

    @Test
    fun `the stash ref is part of the before and after`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("f.txt", "1") } }
        Files.writeString(repo.resolve("f.txt"), "2")
        val rig = Rig(sandbox)
        rig.recorder.run(RawGitCommand(listOf("stash", "push", "--quiet")), repo).getOrThrow()
        val e = rig.log.entries(repo).getOrThrow().single()
        assertNull(e.before.stash)
        assertNotNull(e.after.stash)
    }

    @Test
    fun `a detached HEAD has no branch, and a repository with no commits has no head`(sandbox: GitSandbox) =
        runBlocking {
            val fresh = sandbox.repo("fresh")
            val rig = Rig(sandbox)
            assertEquals(RefState(null, "refs/heads/main", null), rig.recorder.refs(fresh))
            val repo = sandbox.repo { commit("one") { file("f.txt", "1") } }
            sandbox.git(repo, "checkout", "--quiet", "--detach")
            val state = rig.recorder.refs(repo)
            assertNull(state.branch)
            assertEquals(head(sandbox, repo), state.head?.hex)
        }

    @Test
    fun `a linked worktree logs under its own id in the shared file`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("f.txt", "1") } }
        val linked = sandbox.root.resolve("work").resolve("linked")
        sandbox.git(repo, "worktree", "add", "--quiet", "-b", "side", linked.toString())
        val rig = Rig(sandbox)
        rig.commit(linked, "on side").getOrThrow()
        rig.commit(repo, "on main").getOrThrow()
        val entries = rig.log.entries(repo).getOrThrow()
        assertEquals(listOf("main", "wt-linked"), entries.map { it.worktree })
        assertEquals(entries, rig.log.entries(linked).getOrThrow())
    }

    @Test
    fun `a folder that is not a repository runs the command and logs nothing`(sandbox: GitSandbox) = runBlocking {
        val rig = Rig(sandbox)
        val plain = sandbox.dir("plain")
        assertIs<Result.Failure>(rig.recorder.run(RawGitCommand(listOf("add", "x")), plain))
        assertIs<Result.Failure>(rig.log.entries(plain))
    }

    @Test
    fun `no token reaches the log, in argv or in stderr`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("f.txt", "1") } }
        val token = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4"
        val rig = Rig(sandbox)
        rig.recorder.run(RawGitCommand(listOf("config", "user.note", token)), repo).getOrThrow()
        rig.recorder.run(RawGitCommand(listOf("update-ref", "refs/heads/$token", "nonsense")), repo)
        rig.recorder.run(
            RawGitCommand(listOf("remote", "add", "o", "https://user:hunter2secret@example.com/r.git")),
            repo,
        )
        val raw = Files.readString(logFile(sandbox, repo))
        assertFalse(token in raw, raw)
        assertFalse("hunter2secret" in raw, raw)
        assertEquals(3, rig.log.entries(repo).getOrThrow().size)
    }

    // --- the file ---------------------------------------------------------------------------------------------

    @Test
    fun `the file starts with its version, then one line per entry`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("f.txt", "1") } }
        val rig = Rig(sandbox)
        rig.commit(repo, "two").getOrThrow()
        rig.commit(repo, "three").getOrThrow()
        val lines = Files.readAllLines(logFile(sandbox, repo))
        assertEquals("""{"version":1}""", lines[0])
        assertEquals(3, lines.size)
    }

    @Test
    fun `a corrupt line is skipped and the others are read`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("f.txt", "1") } }
        val rig = Rig(sandbox)
        rig.commit(repo, "two").getOrThrow()
        val file = logFile(sandbox, repo)
        Files.writeString(file, Files.readString(file) + "{not json\n" + """{"time":1,"kind":"wat"}""" + "\n")
        rig.commit(repo, "three").getOrThrow()
        val entries = rig.log.entries(repo).getOrThrow()
        assertEquals(listOf("three", "two"), entries.map { it.argv.last() })
    }

    @Test
    fun `the oldest entries are dropped when the log passes its cap`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("f.txt", "1") } }
        val rig = Rig(sandbox, cap = 5)
        repeat(12) { rig.commit(repo, "c$it").getOrThrow() }
        val entries = rig.log.entries(repo).getOrThrow()
        assertEquals(5, entries.size)
        assertEquals("c11", entries.first().argv.last())
        // Never more than the cap plus its slack on disk, and always a header first.
        val lines = Files.readAllLines(logFile(sandbox, repo))
        assertTrue(lines.size <= 1 + 5 + 2 + 1, "${lines.size} lines")
        assertEquals("""{"version":1}""", lines[0])
    }

    @Test
    fun `a log that is over the cap when opened is trimmed on the first write`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("f.txt", "1") } }
        val big = Rig(sandbox, cap = 100)
        repeat(8) { big.commit(repo, "c$it").getOrThrow() }
        // A new process: a small cap, a file already past it.
        val small = Rig(sandbox, cap = 3)
        small.commit(repo, "after").getOrThrow()
        val lines = Files.readAllLines(logFile(sandbox, repo))
        assertEquals(1 + 3 + 1, lines.size)
        assertEquals("after", small.log.entries(repo).getOrThrow().first().argv.last())
    }

    @Test
    fun `a log written by a newer version is read but never written`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("f.txt", "1") } }
        val rig = Rig(sandbox)
        rig.commit(repo, "two").getOrThrow()
        val file = logFile(sandbox, repo)
        val newer = Files.readString(file).replaceFirst("""{"version":1}""", """{"version":2}""")
        Files.writeString(file, newer)
        val again = Rig(sandbox)
        again.commit(repo, "three").getOrThrow()
        assertEquals(newer, Files.readString(file))
        assertEquals(1, again.log.entries(repo).getOrThrow().size)
    }

    @Test
    fun `an entry survives the codec exactly`() {
        val e = entry(OperationKind.AMEND, "ab", "cd", branch = null).copy(stderrTail = "line one\nline \"two\"")
        assertEquals(e, OpLogCodec.decode(OpLogCodec.encode(e)))
        val first = entry(OperationKind.COMMIT, null, "cd").copy(exitCode = null)
        assertEquals(first, OpLogCodec.decode(OpLogCodec.encode(first)))
        assertNull(OpLogCodec.decode(OpLogCodec.header))
    }

    // --- undo and redo ----------------------------------------------------------------------------------------

    @Test
    fun `undo moves the branch back and keeps the change staged, redo moves it forward again`(sandbox: GitSandbox) =
        runBlocking {
            val repo = sandbox.repo { commit("one") { file("f.txt", "1") } }
            val rig = Rig(sandbox)
            val first = head(sandbox, repo)
            Files.writeString(repo.resolve("f.txt"), "2")
            sandbox.git(repo, "add", "f.txt")
            rig.commit(repo, "two").getOrThrow()
            val second = head(sandbox, repo)

            val undone = rig.log.undo(repo).getOrThrow()
            assertEquals(OperationKind.COMMIT, undone.kind)
            assertEquals(first, head(sandbox, repo))
            assertEquals("M  f.txt", sandbox.git(repo, "status", "--porcelain").trimEnd())
            assertEquals("2", Files.readString(repo.resolve("f.txt")))

            rig.log.redo(repo).getOrThrow()
            assertEquals(second, head(sandbox, repo))
            assertEquals("", sandbox.git(repo, "status", "--porcelain").trim())
            assertEquals(
                listOf(OperationKind.REDO, OperationKind.UNDO, OperationKind.COMMIT),
                rig.log.entries(repo).getOrThrow().map {
                    it.kind
                },
            )
        }

    @Test
    fun `undo of an amend restores the commit that was amended`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("f.txt", "1") } }
        val rig = Rig(sandbox)
        rig.commit(repo, "two").getOrThrow()
        val before = head(sandbox, repo)
        rig.commit(repo, "two, better", OperationKind.AMEND, "--amend").getOrThrow()
        assertTrue(head(sandbox, repo) != before)
        assertEquals(OperationKind.AMEND, rig.log.undo(repo).getOrThrow().kind)
        assertEquals(before, head(sandbox, repo))
        // The next Undo is the commit under it.
        assertEquals(OperationKind.COMMIT, rig.log.undoState(repo).getOrThrow().undo?.kind)
    }

    @Test
    fun `undo of the first commit leaves a branch with no commits, and redo brings it back`(sandbox: GitSandbox) =
        runBlocking {
            val repo = sandbox.repo()
            val rig = Rig(sandbox)
            Files.writeString(repo.resolve("f.txt"), "1")
            sandbox.git(repo, "add", "f.txt")
            rig.commit(repo, "root").getOrThrow()
            val root = head(sandbox, repo)
            rig.log.undo(repo).getOrThrow()
            assertNull(rig.recorder.refs(repo).head)
            rig.log.redo(repo).getOrThrow()
            assertEquals(root, head(sandbox, repo))
        }

    @Test
    fun `undo on a detached HEAD moves HEAD`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("f.txt", "1") } }
        sandbox.git(repo, "checkout", "--quiet", "--detach")
        val rig = Rig(sandbox)
        val first = head(sandbox, repo)
        rig.commit(repo, "two").getOrThrow()
        rig.log.undo(repo).getOrThrow()
        assertEquals(first, head(sandbox, repo))
        assertEquals("HEAD", sandbox.git(repo, "rev-parse", "--abbrev-ref", "HEAD").trim())
    }

    @Test
    fun `undo works after a restart, from the file alone`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("f.txt", "1") } }
        val first = head(sandbox, repo)
        Rig(sandbox).commit(repo, "two").getOrThrow()
        val restarted = Rig(sandbox)
        assertNotNull(restarted.log.undoState(repo).getOrThrow().undo)
        restarted.log.undo(repo).getOrThrow()
        assertEquals(first, head(sandbox, repo))
        // And so does the Redo, in a third process.
        Rig(sandbox).log.redo(repo).getOrThrow()
        assertTrue(head(sandbox, repo) != first)
    }

    @Test
    fun `undo is refused after a commit made outside Graft, and nothing moves`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("f.txt", "1") } }
        val rig = Rig(sandbox)
        rig.commit(repo, "two").getOrThrow()
        sandbox.commit(repo, "from the terminal", "g.txt")
        val tip = head(sandbox, repo)
        val result = rig.log.undo(repo)
        assertIs<Result.Failure>(result)
        assertIs<GitUndoRefusedException>(result.error)
        assertEquals("GIT-R-UNDO-MOVD", result.error.code)
        assertTrue("moved" in (result.error as GitUndoRefusedException).reason)
        assertEquals(tip, head(sandbox, repo))
        assertEquals(1, rig.log.entries(repo).getOrThrow().size)
    }

    @Test
    fun `undo is refused on another branch, and redo after a new commit`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("f.txt", "1") } }
        val rig = Rig(sandbox)
        rig.commit(repo, "two").getOrThrow()
        sandbox.git(repo, "branch", "other", "HEAD~1")
        sandbox.git(repo, "checkout", "--quiet", "other")
        assertIs<GitUndoRefusedException>((rig.log.undo(repo) as Result.Failure).error)
        sandbox.git(repo, "checkout", "--quiet", "main")
        rig.log.undo(repo).getOrThrow()
        // A new commit empties what Redo could do; the old one is gone for good.
        rig.commit(repo, "three").getOrThrow()
        assertNull(rig.log.undoState(repo).getOrThrow().redo)
        assertIs<GitUndoRefusedException>((rig.log.redo(repo) as Result.Failure).error)
    }

    @Test
    fun `nothing to undo is a refusal, not a crash`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("f.txt", "1") } }
        val result = Rig(sandbox).log.undo(repo)
        assertIs<GitUndoRefusedException>((result as Result.Failure).error)
    }

    @Test
    fun `a failed commit is not something Undo undoes`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("f.txt", "1") } }
        val rig = Rig(sandbox)
        // Nothing staged and no --allow-empty: git refuses.
        rig.recorder.run(RawGitCommand(listOf("commit", "--quiet", "-m", "empty")), repo, OperationKind.COMMIT)
        assertNull(rig.log.undoState(repo).getOrThrow().undo)
    }

    @Test
    fun `staging is recorded as an operation but is not undone by Undo`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("f.txt", "1") } }
        val rig = Rig(sandbox)
        Files.writeString(repo.resolve("f.txt"), "2")
        rig.recorder.run(RawGitCommand(listOf("add", "f.txt")), repo)
        val e = rig.log.entries(repo).getOrThrow().single()
        assertEquals(OperationKind.OTHER, e.kind)
        assertNull(rig.log.undoState(repo).getOrThrow().undo)
    }

    // --- the undo stacks, as a pure function ------------------------------------------------------------------

    @Test
    fun `replay follows commit, undo and redo like an editor's history`() {
        val a = entry(OperationKind.COMMIT, "00", "a1")
        val b = entry(OperationKind.AMEND, "a1", "b1")
        val undo = entry(OperationKind.UNDO, "b1", "a1")
        val redo = entry(OperationKind.REDO, "a1", "b1")
        assertEquals(b, UndoReplay.replay(listOf(a, b)).undo)
        UndoReplay.replay(listOf(a, b, undo)).let {
            assertEquals(a, it.undo)
            assertEquals(b, it.redo)
        }
        UndoReplay.replay(listOf(a, b, undo, redo)).let {
            assertEquals(b, it.undo)
            assertNull(it.redo)
        }
        // A commit after an Undo ends the redo.
        val c = entry(OperationKind.COMMIT, "a1", "c1")
        UndoReplay.replay(listOf(a, b, undo, c)).let {
            assertEquals(c, it.undo)
            assertNull(it.redo)
        }
        // Failed and cancelled entries, and entries that moved nothing, are ignored.
        val failed = entry(OperationKind.COMMIT, "b1", "b1", outcome = OperationOutcome.FAILED)
        assertEquals(a, UndoReplay.replay(listOf(a, failed)).undo)
        assertNull(UndoReplay.replay(listOf(entry(OperationKind.COMMIT, "a1", "a1"))).undo)
    }
}
