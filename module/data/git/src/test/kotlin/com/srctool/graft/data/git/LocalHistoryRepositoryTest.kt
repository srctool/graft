// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.graft.data.git.api.models.ChangeSelection
import com.srctool.graft.data.git.api.models.FileSelection
import com.srctool.graft.data.git.api.models.FileSelection.Scope
import com.srctool.graft.data.git.api.models.LocalHistoryLimits
import com.srctool.graft.data.git.implementation.repositories.GitLocalHistoryRepository
import com.srctool.graft.data.git.implementation.repositories.GitStagingRepository
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Local History against real repositories: what a snapshot holds, how the chain behaves, and how it is cut back. */
@ExtendWith(GitSandboxExtension::class)
class LocalHistoryRepositoryTest {
    private var clock = 1_800_000_000L

    private fun history(sandbox: GitSandbox, limits: LocalHistoryLimits = LocalHistoryLimits()) =
        GitLocalHistoryRepository(sandbox.runner(), limits) { clock }

    private fun write(repo: Path, name: String, text: String) {
        val file = repo.resolve(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private fun files(sandbox: GitSandbox, repo: Path, id: Any) =
        sandbox.git(repo, "ls-tree", "-r", "--name-only", id.toString()).lines().filter { it.isNotEmpty() }

    private fun base(sandbox: GitSandbox) = sandbox.repo {
        commit("one") {
            file(".gitignore", "*.log\nbuild/\n")
            file("tracked.txt", "1\n")
        }
    }

    @Test
    fun `a snapshot holds tracked and untracked files, not ignored ones`(sandbox: GitSandbox) = runBlocking {
        val repo = base(sandbox)
        write(repo, "tracked.txt", "2\n")
        write(repo, "new.txt", "n\n")
        write(repo, "debug.log", "noise\n")
        write(repo, "build/out.bin", "x\n")
        val result = history(sandbox).snapshot(repo).getOrThrow()
        assertTrue(result.created)
        assertEquals(listOf(".gitignore", "new.txt", "tracked.txt"), files(sandbox, repo, result.snapshot.id))
        // It holds the working tree's content, not the index's or HEAD's.
        assertEquals("2\n", sandbox.git(repo, "show", "${result.snapshot.id}:tracked.txt"))
    }

    @Test
    fun `an edit made right after the index was written is not missed`(sandbox: GitSandbox) = runBlocking {
        val repo = base(sandbox)
        // Compare only the modification second and the size, as git on a coarse clock effectively does.
        sandbox.git(repo, "config", "core.checkStat", "minimal")
        sandbox.git(repo, "config", "core.trustCtime", "false")
        sandbox.git(repo, "status", "--porcelain")
        write(repo, "tracked.txt", "2\n") // same size as "1\n"
        Files.setLastModifiedTime(repo.resolve("tracked.txt"), Files.getLastModifiedTime(repo.resolve(".git/index")))
        val id = history(sandbox).snapshot(repo).getOrThrow().snapshot.id
        assertEquals("2\n", sandbox.git(repo, "show", "${id.hex}:tracked.txt"))
    }

    @Test
    fun `a snapshot leaves the index, the files and the status exactly as they were`(sandbox: GitSandbox) =
        runBlocking {
            val repo = base(sandbox)
            write(repo, "tracked.txt", "2\n")
            sandbox.git(repo, "add", "tracked.txt")
            write(repo, "tracked.txt", "3\n")
            write(repo, "new.txt", "n\n")
            // `git status` itself refreshes the index, so read it after one.
            val status = sandbox.git(repo, "status", "--porcelain")
            val index = Files.readAllBytes(repo.resolve(".git/index")).toList()
            history(sandbox).snapshot(repo).getOrThrow()
            assertEquals(index, Files.readAllBytes(repo.resolve(".git/index")).toList())
            assertEquals(status, sandbox.git(repo, "status", "--porcelain"))
            // The scratch index is gone.
            assertEquals(emptyList(), Files.list(repo.resolve(".git/graft/tmp")).use { it.toList() })
            // And the history is only under its own ref.
            assertEquals(
                "refs/graft/history/main",
                sandbox.git(repo, "for-each-ref", "--format=%(refname)", "refs/graft").trim(),
            )
        }

    @Test
    fun `the same tree is not snapshotted twice, but a label always is`(sandbox: GitSandbox) = runBlocking {
        val repo = base(sandbox)
        val history = history(sandbox)
        val first = history.snapshot(repo).getOrThrow()
        clock += 60
        val again = history.snapshot(repo).getOrThrow()
        assertFalse(again.created)
        assertEquals(first.snapshot.id, again.snapshot.id)
        val labelled = history.snapshot(repo, "before refactor").getOrThrow()
        assertTrue(labelled.created)
        assertEquals("before refactor", labelled.snapshot.label)
        assertEquals(first.snapshot.tree, labelled.snapshot.tree)
        assertEquals(first.snapshot.id, labelled.snapshot.parent)
    }

    @Test
    fun `snapshots form a chain, newest first, under the worktree's ref`(sandbox: GitSandbox) = runBlocking {
        val repo = base(sandbox)
        val history = history(sandbox)
        val ids = (1..3).map { n ->
            write(repo, "tracked.txt", "v$n\n")
            clock += 10
            history.snapshot(repo).getOrThrow().snapshot
        }
        val listed = history.snapshots(repo).getOrThrow()
        assertEquals(ids.reversed().map { it.id }, listed.map { it.id })
        assertEquals(listOf(ids[1].id, ids[0].id, null), listed.map { it.parent })
        assertEquals(ids.last().id.hex, sandbox.git(repo, "rev-parse", "refs/graft/history/main").trim())
        assertEquals(1, history.snapshots(repo, limit = 1).getOrThrow().size)
    }

    @Test
    fun `a snapshot carries the fixed Graft identity and the version trailer`(sandbox: GitSandbox) = runBlocking {
        val repo = base(sandbox)
        val id = history(sandbox).snapshot(repo, "before refactor").getOrThrow().snapshot.id
        assertEquals(
            "Graft <graft@localhost> Graft <graft@localhost>",
            sandbox.git(repo, "log", "-1", "--format=%an <%ae> %cn <%ce>", id.hex).trim(),
        )
        val message = sandbox.git(repo, "log", "-1", "--format=%B", id.hex)
        assertTrue(message.startsWith("before refactor\n"), message)
        assertTrue("Graft-History-Version: 1\n" in message, message)
        assertTrue("Graft-Label: before refactor\n" in message, message)
        assertEquals(1_800_000_000L, sandbox.git(repo, "log", "-1", "--format=%ct", id.hex).trim().toLong())
    }

    @Test
    fun `files over the limit are skipped`(sandbox: GitSandbox) = runBlocking {
        val repo = base(sandbox)
        write(repo, "small.txt", "ok\n")
        write(repo, "huge.bin", "x".repeat(500))
        val id = history(sandbox, LocalHistoryLimits(maxFileBytes = 100)).snapshot(repo).getOrThrow().snapshot.id
        assertEquals(listOf(".gitignore", "small.txt", "tracked.txt"), files(sandbox, repo, id))
    }

    @Test
    fun `a snapshot works in the middle of a conflicted merge`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("base") { file("a.txt", "base\n") } }
        sandbox.git(repo, "checkout", "-q", "-b", "side")
        write(repo, "a.txt", "side\n")
        sandbox.git(repo, "commit", "-qam", "side")
        sandbox.git(repo, "checkout", "-q", "main")
        write(repo, "a.txt", "main\n")
        sandbox.git(repo, "commit", "-qam", "main")
        runCatching { sandbox.git(repo, "merge", "side") } // conflicts, exits 1
        assertTrue(sandbox.git(repo, "status", "--porcelain").contains("UU a.txt"))
        val id = history(sandbox).snapshot(repo).getOrThrow().snapshot.id
        assertTrue(sandbox.git(repo, "show", "${id.hex}:a.txt").contains("<<<<<<<"))
    }

    @Test
    fun `a nested repository is left out`(sandbox: GitSandbox) = runBlocking {
        val repo = base(sandbox)
        val inner = repo.resolve("inner")
        Files.createDirectories(inner)
        sandbox.git(inner, "init", "-q")
        write(repo, "inner/file.txt", "x\n")
        write(repo, "outer.txt", "y\n")
        val id = history(sandbox).snapshot(repo).getOrThrow().snapshot.id
        assertTrue("outer.txt" in files(sandbox, repo, id))
        assertFalse(files(sandbox, repo, id).any { it.startsWith("inner") })
    }

    @Test
    fun `a file's versions, deleted files, and what each snapshot changed`(sandbox: GitSandbox) = runBlocking {
        val repo = base(sandbox)
        val history = history(sandbox)
        write(repo, "doc.txt", "one\n")
        clock += 10
        val s1 = history.snapshot(repo).getOrThrow().snapshot
        write(repo, "doc.txt", "two\n")
        clock += 10
        val s2 = history.snapshot(repo).getOrThrow().snapshot
        Files.delete(repo.resolve("doc.txt"))
        clock += 10
        val s3 = history.snapshot(repo).getOrThrow().snapshot

        val versions = history.versions(repo, "doc.txt").getOrThrow()
        assertEquals(listOf(s3.id, s2.id, s1.id), versions.map { it.snapshot.id })
        assertNull(versions.first().blob)
        assertEquals("two\n", sandbox.git(repo, "cat-file", "-p", versions[1].blob!!.hex))

        assertEquals(listOf("doc.txt"), history.deletedSince(repo, s2.id).getOrThrow())
        assertEquals(emptyList(), history.deletedSince(repo, s3.id).getOrThrow())
        assertEquals(listOf("doc.txt"), history.changes(repo, s3.id).getOrThrow().map { it.path })
        // The first snapshot has no parent: everything in it is new.
        assertTrue(history.changes(repo, s1.id).getOrThrow().any { it.path == "doc.txt" })
    }

    @Test
    fun `a deleted file comes back, and the index is not touched`(sandbox: GitSandbox) = runBlocking {
        val repo = base(sandbox)
        val history = history(sandbox)
        write(repo, "doc.txt", "keep me\n")
        val snapshot = history.snapshot(repo).getOrThrow().snapshot
        Files.delete(repo.resolve("doc.txt"))
        val index = Files.readAllBytes(repo.resolve(".git/index")).toList()
        history.restoreFile(repo, snapshot.id, "doc.txt").getOrThrow()
        assertEquals("keep me\n", Files.readString(repo.resolve("doc.txt")))
        assertEquals(index, Files.readAllBytes(repo.resolve(".git/index")).toList())
    }

    @Test
    fun `one hunk is restored and the other stays`(sandbox: GitSandbox) = runBlocking {
        val numbered = (1..20).joinToString("") { "l$it\n" }
        val repo = sandbox.repo { commit("one") { file("f.txt", numbered) } }
        val history = history(sandbox)
        val snapshot = history.snapshot(repo, "start").getOrThrow().snapshot
        write(repo, "f.txt", (1..20).joinToString("") { (if (it == 2 || it == 18) "X$it" else "l$it") + "\n" })
        history.restoreHunks(repo, snapshot.id, "f.txt", setOf(0)).getOrThrow()
        assertEquals(
            (1..20).joinToString("") {
                (
                    if (it ==
                        18
                    ) {
                        "X$it"
                    } else {
                        "l$it"
                    }
                    ) + "\n"
            },
            Files.readString(repo.resolve("f.txt")),
        )
        val refused = history.restoreHunks(repo, snapshot.id, "f.txt", setOf(7))
        assertIs<Result.Failure>(refused)
        assertEquals("GIT-R-APLY-PAT", refused.error.code)
    }

    @Test
    fun `restoring everything puts files back, removes new ones, and can itself be undone`(sandbox: GitSandbox) =
        runBlocking {
            val repo = base(sandbox)
            val history = history(sandbox)
            write(repo, "tracked.txt", "as it was\n")
            write(repo, "old.txt", "old\n")
            val snapshot = history.snapshot(repo, "good state").getOrThrow().snapshot
            write(repo, "tracked.txt", "broken\n")
            Files.delete(repo.resolve("old.txt"))
            write(repo, "made-since.txt", "new\n")
            clock += 30

            val before = history.restoreAll(repo, snapshot.id).getOrThrow()

            assertEquals("as it was\n", Files.readString(repo.resolve("tracked.txt")))
            assertEquals("old\n", Files.readString(repo.resolve("old.txt")))
            assertFalse(Files.exists(repo.resolve("made-since.txt")))
            assertEquals("before restore", before.snapshot.label)
            // The snapshot taken first has the state the restore replaced, so it can be restored in turn.
            assertEquals("broken\n", sandbox.git(repo, "show", "${before.snapshot.id}:tracked.txt"))
            assertTrue("made-since.txt" in files(sandbox, repo, before.snapshot.id))
            history.restoreAll(repo, before.snapshot.id).getOrThrow()
            assertEquals("broken\n", Files.readString(repo.resolve("tracked.txt")))
            assertTrue(Files.exists(repo.resolve("made-since.txt")))
        }

    @Test
    fun `search finds a path and a text across snapshots`(sandbox: GitSandbox) = runBlocking {
        val repo = base(sandbox)
        val history = history(sandbox)
        write(repo, "src/Parser.kt", "val token = 1\n")
        clock += 10
        val first = history.snapshot(repo).getOrThrow().snapshot
        write(repo, "src/Parser.kt", "val secret = 2\n")
        clock += 10
        val second = history.snapshot(repo).getOrThrow().snapshot

        val byPath = history.searchPaths(repo, "parser").getOrThrow()
        assertEquals(listOf(second.id, first.id), byPath.map { it.snapshot.id })
        assertEquals(listOf("src/Parser.kt"), byPath.first().paths)
        assertEquals(emptyList(), history.searchPaths(repo, "nothing-like-this").getOrThrow())

        val byText = history.searchText(repo, "secret").getOrThrow()
        assertEquals(listOf(second.id), byText.map { it.snapshot.id })
        assertEquals(emptyList(), history.searchText(repo, "").getOrThrow())
    }

    @Test
    fun `search on a repository with no history finds nothing`(sandbox: GitSandbox) = runBlocking {
        val repo = base(sandbox)
        assertEquals(emptyList(), history(sandbox).searchText(repo, "x").getOrThrow())
        assertEquals(emptyList(), history(sandbox).snapshots(repo).getOrThrow())
    }

    @Test
    fun `purging a file removes it from every snapshot and keeps the rest`(sandbox: GitSandbox) = runBlocking {
        val repo = base(sandbox)
        val history = history(sandbox)
        write(repo, "secrets.env", "TOKEN=abc\n")
        write(repo, "notes.txt", "1\n")
        clock += 10
        history.snapshot(repo, "labelled").getOrThrow()
        write(repo, "notes.txt", "2\n")
        clock += 10
        history.snapshot(repo).getOrThrow()
        Files.delete(repo.resolve("secrets.env"))
        clock += 10
        history.snapshot(repo).getOrThrow()

        val rewritten = history.purge(repo, "secrets.env").getOrThrow()

        assertEquals(2, rewritten)
        assertEquals(emptyList(), history.versions(repo, "secrets.env").getOrThrow())
        assertEquals(emptyList(), history.searchText(repo, "TOKEN=abc").getOrThrow())
        val chain = history.snapshots(repo).getOrThrow()
        assertEquals(3, chain.size)
        assertEquals("labelled", chain.last().label)
        assertEquals(
            listOf(clock - 20, clock - 10, clock).reversed().reversed().let {
                listOf(
                    clock,
                    clock - 10,
                    clock - 20,
                )
            },
            chain.map { it.time },
        )
        assertEquals("2\n", sandbox.git(repo, "show", "${chain[1].id}:notes.txt"))
        assertTrue(chain.zipWithNext().all { (newer, older) -> newer.parent == older.id })
    }

    // --- retention ---------------------------------------------------------------------------------------------

    private fun at(
        sandbox: GitSandbox,
        repo: Path,
        history: GitLocalHistoryRepository,
        now: Long,
        secondsAgo: Long,
        label: String? = null,
    ) = runBlocking {
        clock = now - secondsAgo
        write(repo, "f.txt", "at $clock\n")
        history.snapshot(repo, label).getOrThrow().snapshot
    }

    @Test
    fun `thinning keeps today's snapshots, one an hour, one a day, labelled ones, and the newest`(sandbox: GitSandbox) =
        runBlocking {
            val repo = base(sandbox)
            val history = history(sandbox)
            val now = 1_800_000_000L
            val hour = 3_600L
            val day = 24 * hour
            // Oldest first, as they would have been taken.
            val old = at(sandbox, repo, history, now, 40 * day, "keep me")
            val older = at(sandbox, repo, history, now, 40 * day - hour)
            val monthA = at(sandbox, repo, history, now, 20 * day + 2 * hour)
            val monthB = at(sandbox, repo, history, now, 20 * day + hour) // same day, newer: this one stays
            val weekA = at(sandbox, repo, history, now, 3 * day + 600)
            val weekB = at(sandbox, repo, history, now, 3 * day + 300) // same hour, newer: this one stays
            val today1 = at(sandbox, repo, history, now, 2 * hour)
            val today2 = at(sandbox, repo, history, now, 1 * hour)
            val tip = at(sandbox, repo, history, now, 600)
            clock = now

            val result = history.thin(repo).getOrThrow()

            val kept = history.snapshots(repo).getOrThrow()
            assertEquals(
                listOf(tip.time, today2.time, today1.time, weekB.time, monthB.time, old.time),
                kept.map {
                    it.time
                },
            )
            assertEquals(6, result.kept)
            assertEquals(3, result.dropped)
            assertTrue(setOf(older.time, monthA.time, weekA.time).none { t -> kept.any { it.time == t } })
            // The chain is whole and each kept snapshot still holds what it held.
            assertTrue(kept.zipWithNext().all { (newer, older2) -> newer.parent == older2.id })
            assertNull(kept.last().parent)
            assertEquals("at ${tip.time}\n", sandbox.git(repo, "show", "${kept.first().id}:f.txt"))
            assertEquals("at ${old.time}\n", sandbox.git(repo, "show", "${kept.last().id}:f.txt"))
            assertEquals("keep me", kept.last().label)
        }

    @Test
    fun `thinning with nothing to drop writes nothing`(sandbox: GitSandbox) = runBlocking {
        val repo = base(sandbox)
        val history = history(sandbox)
        val first = history.snapshot(repo).getOrThrow().snapshot
        val result = history.thin(repo).getOrThrow()
        assertEquals(0, result.dropped)
        assertEquals(first.id, history.snapshots(repo).getOrThrow().single().id)
    }

    @Test
    fun `over the budget the oldest unlabelled snapshots go first`(sandbox: GitSandbox) = runBlocking {
        val repo = base(sandbox)
        val history = history(sandbox, LocalHistoryLimits(budgetBytes = 1))
        write(repo, "a.txt", "x".repeat(2_000))
        clock += 10
        history.snapshot(repo, "important").getOrThrow()
        for (n in 1..4) {
            write(repo, "a.txt", "$n".repeat(2_000))
            clock += 10
            history.snapshot(repo).getOrThrow()
        }
        val result = history.thin(repo).getOrThrow()
        val kept = history.snapshots(repo).getOrThrow()
        assertTrue(result.dropped >= 3, "dropped ${result.dropped}")
        // The budget can't be met without losing what is labelled or the newest, so those stay.
        assertEquals("important", kept.last().label)
        assertEquals(clock, kept.first().time)
    }

    @Test
    fun `a removed worktree's history is kept for 30 days, then dropped`(sandbox: GitSandbox) = runBlocking {
        val repo = base(sandbox)
        val linked = sandbox.dir("work").resolve("linked")
        sandbox.git(repo, "worktree", "add", "-q", "-b", "side", linked.toString())
        val history = history(sandbox)
        write(linked, "wip.txt", "work\n")
        history.snapshot(linked).getOrThrow()
        write(repo, "main.txt", "m\n")
        history.snapshot(repo).getOrThrow()
        assertEquals(
            setOf("refs/graft/history/main", "refs/graft/history/wt-linked"),
            sandbox.git(repo, "for-each-ref", "--format=%(refname)", "refs/graft/history").lines().filter {
                it.isNotEmpty()
            }.toSet(),
        )
        sandbox.git(repo, "worktree", "remove", "--force", linked.toString())
        // Its history survives the worktree.
        clock += 10 * 24 * 3_600
        assertEquals(0, history.thin(repo).getOrThrow().removedHistories)
        assertTrue(sandbox.git(repo, "for-each-ref", "refs/graft/history/wt-linked").isNotBlank())
        clock += 25 * 24 * 3_600
        assertEquals(1, history.thin(repo).getOrThrow().removedHistories)
        assertTrue(sandbox.git(repo, "for-each-ref", "refs/graft/history/wt-linked").isBlank())
        // The main worktree's own history is never treated as removed.
        assertTrue(sandbox.git(repo, "for-each-ref", "refs/graft/history/main").isNotBlank())
    }

    @Test
    fun `each worktree has its own chain`(sandbox: GitSandbox) = runBlocking {
        val repo = base(sandbox)
        val linked = sandbox.dir("work").resolve("second")
        sandbox.git(repo, "worktree", "add", "-q", "-b", "side", linked.toString())
        val history = history(sandbox)
        write(linked, "only-here.txt", "x\n")
        val inLinked = history.snapshot(linked).getOrThrow().snapshot
        val inMain = history.snapshot(repo).getOrThrow().snapshot
        assertNotEquals(inLinked.id, inMain.id)
        assertEquals(listOf(inLinked.id), history.snapshots(linked).getOrThrow().map { it.id })
        assertEquals(listOf(inMain.id), history.snapshots(repo).getOrThrow().map { it.id })
        assertTrue("only-here.txt" in files(sandbox, linked, inLinked.id))
        assertFalse("only-here.txt" in files(sandbox, repo, inMain.id))
    }

    @Test
    fun `a plain push never sends the history`(sandbox: GitSandbox) = runBlocking {
        val repo = base(sandbox)
        val remote = sandbox.dir("remote.git")
        sandbox.git(remote, "init", "-q", "--bare")
        sandbox.git(repo, "remote", "add", "origin", remote.toString())
        history(sandbox).snapshot(repo, "mine").getOrThrow()
        sandbox.git(repo, "push", "-q", "origin", "main")
        sandbox.git(repo, "push", "-q", "--all", "origin")
        sandbox.git(repo, "push", "-q", "--tags", "origin")
        assertFalse(sandbox.git(repo, "ls-remote", "origin").contains("refs/graft"))
    }

    @Test
    fun `a folder that is not a repository is a failure`(sandbox: GitSandbox) = runBlocking {
        assertIs<Result.Failure>(history(sandbox).snapshot(sandbox.dir("plain")))
        Unit
    }

    // --- discard takes the snapshot ---------------------------------------------------------------------------

    @Test
    fun `a discard is taken after a snapshot that still has what it threw away`(sandbox: GitSandbox) = runBlocking {
        val repo = base(sandbox)
        val history = history(sandbox)
        val staging = GitStagingRepository(sandbox.runner(), history)
        write(repo, "tracked.txt", "precious edit\n")
        write(repo, "untracked.txt", "precious new file\n")
        val selection = ChangeSelection(
            listOf(FileSelection("tracked.txt", Scope.WholeFile), FileSelection("untracked.txt", Scope.WholeFile)),
        )
        staging.discard(repo, selection).getOrThrow()

        assertEquals("1\n", Files.readString(repo.resolve("tracked.txt")))
        assertFalse(Files.exists(repo.resolve("untracked.txt")))
        val snapshot = history.snapshots(repo).getOrThrow().single()
        assertEquals("before discard", snapshot.label)
        assertEquals("precious edit\n", sandbox.git(repo, "show", "${snapshot.id}:tracked.txt"))
        assertEquals("precious new file\n", sandbox.git(repo, "show", "${snapshot.id}:untracked.txt"))
        // And it can be put back.
        history.restoreAll(repo, snapshot.id).getOrThrow()
        assertEquals("precious edit\n", Files.readString(repo.resolve("tracked.txt")))
        assertEquals("precious new file\n", Files.readString(repo.resolve("untracked.txt")))
    }

    @Test
    fun `a discard that cannot be snapshotted discards nothing`(sandbox: GitSandbox) = runBlocking {
        val repo = base(sandbox)
        write(repo, "tracked.txt", "edit\n")
        // A file where the scratch folder should be makes the snapshot fail.
        Files.createDirectories(repo.resolve(".git/graft"))
        Files.writeString(repo.resolve(".git/graft/tmp"), "in the way")
        val staging = GitStagingRepository(sandbox.runner(), history(sandbox))
        val result = staging.discard(repo, ChangeSelection(listOf(FileSelection("tracked.txt", Scope.WholeFile))))
        assertIs<Result.Failure>(result)
        assertEquals("edit\n", Files.readString(repo.resolve("tracked.txt")))
    }

    @Test
    fun `a stale discard takes no snapshot`(sandbox: GitSandbox) = runBlocking {
        val repo = base(sandbox)
        write(repo, "tracked.txt", "edit\n")
        val history = history(sandbox)
        val staging = GitStagingRepository(sandbox.runner(), history)
        val stale = staging.discard(repo, ChangeSelection(listOf(FileSelection("tracked.txt", Scope.Hunks(setOf(4))))))
        assertIs<Result.Failure>(stale)
        assertEquals(emptyList(), history.snapshots(repo).getOrThrow())
    }
}
