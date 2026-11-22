// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.CommitProblem
import com.srctool.graft.core.git.GitCommitRefusedException
import com.srctool.graft.core.git.GitExitException
import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.graft.data.git.api.models.ChangeSelection
import com.srctool.graft.data.git.api.models.CoAuthor
import com.srctool.graft.data.git.api.models.CommitDraft
import com.srctool.graft.data.git.api.models.CommitGuide
import com.srctool.graft.data.git.api.models.CommitMessageText
import com.srctool.graft.data.git.api.models.FileSelection
import com.srctool.graft.data.git.api.models.FileSelection.Scope
import com.srctool.graft.data.git.api.models.OperationKind
import com.srctool.graft.data.git.api.models.OperationOutcome
import com.srctool.graft.data.git.implementation.repositories.GitCommittingRepository
import com.srctool.graft.data.git.implementation.repositories.GitLocalHistoryRepository
import com.srctool.graft.data.git.implementation.repositories.GitOperationLogRepository
import com.srctool.graft.data.git.implementation.repositories.GitStagingRepository
import com.srctool.graft.data.git.implementation.repositories.MutationRecorder
import com.srctool.graft.data.git.implementation.source.local.OpLogStore
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Commit and amend against real repositories, with the pure parts (the message, the guide, co-authors) alongside. */
@ExtendWith(GitSandboxExtension::class)
class CommittingRepositoryTest {
    private class Rig(sandbox: GitSandbox) {
        val runner = sandbox.runner()
        private val store = OpLogStore()
        private val recorder = MutationRecorder(runner, store)
        val staging = GitStagingRepository(runner, GitLocalHistoryRepository(runner), recorder)
        val commits = GitCommittingRepository(runner, staging, recorder)
        val log = GitOperationLogRepository(recorder, store)
    }

    private fun write(repo: Path, file: String, text: String) = Files.writeString(repo.resolve(file), text)

    private fun head(sandbox: GitSandbox, repo: Path) = sandbox.git(repo, "rev-parse", "HEAD").trim()

    private fun message(sandbox: GitSandbox, repo: Path, rev: String = "HEAD") =
        sandbox.git(repo, "log", "-1", "--format=%B", rev).trimEnd()

    private fun stageFile(sandbox: GitSandbox, repo: Path, file: String, text: String) {
        write(repo, file, text)
        sandbox.git(repo, "add", file)
    }

    private fun hook(repo: Path, name: String, body: String) {
        val file = repo.resolve(".git/hooks").also { Files.createDirectories(it) }.resolve(name)
        Files.writeString(file, "#!/bin/sh\n$body\n")
        file.toFile().setExecutable(true)
    }

    // --- commit -----------------------------------------------------------------------------------------------

    @Test
    fun `committing the index makes a commit with the summary and description`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1") } }
        stageFile(sandbox, repo, "b.txt", "2")
        val id = Rig(sandbox).commits.commit(repo, CommitDraft("Add b", "Because it is needed.")).getOrThrow()
        assertEquals(head(sandbox, repo), id.hex)
        assertEquals("Add b\n\nBecause it is needed.", message(sandbox, repo))
        assertEquals("", sandbox.git(repo, "status", "--porcelain").trim())
    }

    @Test
    fun `the first commit of an empty repository works`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo()
        stageFile(sandbox, repo, "a.txt", "1")
        val id = Rig(sandbox).commits.commit(repo, CommitDraft("First")).getOrThrow()
        assertEquals(head(sandbox, repo), id.hex)
        assertEquals("First", message(sandbox, repo))
    }

    @Test
    fun `lines starting with a hash stay in the message`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1") } }
        stageFile(sandbox, repo, "b.txt", "2")
        Rig(sandbox).commits.commit(repo, CommitDraft("Fix #12", "# not a comment\nFixes #12")).getOrThrow()
        assertEquals("Fix #12\n\n# not a comment\nFixes #12", message(sandbox, repo))
    }

    @Test
    fun `sign-off and co-authors become trailers`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1") } }
        stageFile(sandbox, repo, "b.txt", "2")
        val draft =
            CommitDraft(
                summary = "Add b",
                description = "Body.",
                signOff = true,
                coAuthors = listOf(CoAuthor("Ada Lovelace", "ada@example.org"), CoAuthor("Alan T", "alan@example.org")),
            )
        Rig(sandbox).commits.commit(repo, draft).getOrThrow()
        val lines = message(sandbox, repo).lines()
        assertEquals("Add b", lines.first())
        assertContains(lines, "Co-authored-by: Ada Lovelace <ada@example.org>")
        assertContains(lines, "Co-authored-by: Alan T <alan@example.org>")
        assertTrue(lines.any { it.startsWith("Signed-off-by: ") })
        // Trailers sit together at the end, after a blank line.
        val firstTrailer = lines.indexOfFirst { it.startsWith("Signed-off-by") || it.startsWith("Co-authored") }
        assertEquals("", lines[firstTrailer - 1])
    }

    @Test
    fun `an empty commit is refused and nothing is written`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1") } }
        val before = head(sandbox, repo)
        val rig = Rig(sandbox)
        val result = rig.commits.commit(repo, CommitDraft("Nothing"))
        assertIs<Result.Failure>(result)
        val error = assertIs<GitCommitRefusedException>(result.error)
        assertEquals(CommitProblem.NOTHING_TO_COMMIT, error.problem)
        assertEquals("GIT-R-CMIT-REFU", error.code)
        assertEquals(before, head(sandbox, repo))
        assertTrue(rig.log.entries(repo).getOrThrow().isEmpty())
    }

    @Test
    fun `an empty commit goes through when it is asked for`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1") } }
        val id = Rig(sandbox).commits.commit(repo, CommitDraft("Marker", allowEmpty = true)).getOrThrow()
        assertEquals(head(sandbox, repo), id.hex)
    }

    @Test
    fun `a blank summary and a malformed co-author are refused before git runs`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1") } }
        stageFile(sandbox, repo, "b.txt", "2")
        val rig = Rig(sandbox)
        val blank = rig.commits.commit(repo, CommitDraft("   ")) as Result.Failure
        assertEquals(CommitProblem.NO_SUMMARY, (blank.error as GitCommitRefusedException).problem)
        val broken = CommitDraft("x", coAuthors = listOf(CoAuthor("A\nB", "a@b.c")))
        val bad = rig.commits.commit(repo, broken) as Result.Failure
        assertEquals(CommitProblem.BAD_CO_AUTHOR, (bad.error as GitCommitRefusedException).problem)
        assertEquals("one", message(sandbox, repo))
    }

    @Test
    fun `a failing pre-commit hook commits nothing and keeps the hook's output`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1") } }
        hook(repo, "pre-commit", "echo 'lint failed: b.txt' >&2\nexit 1")
        stageFile(sandbox, repo, "b.txt", "2")
        val before = head(sandbox, repo)
        val rig = Rig(sandbox)
        val result = rig.commits.commit(repo, CommitDraft("Add b"))
        assertIs<Result.Failure>(result)
        val error = assertIs<GitExitException>(result.error)
        assertContains(error.stderr, "lint failed: b.txt")
        assertEquals(before, head(sandbox, repo))
        val entry = rig.log.entries(repo).getOrThrow().single()
        assertEquals(OperationKind.COMMIT, entry.kind)
        assertEquals(OperationOutcome.FAILED, entry.outcome)
        assertContains(entry.stderrTail, "lint failed: b.txt")
        // The staged file is still staged: nothing was lost.
        assertEquals("A  b.txt", sandbox.git(repo, "status", "--porcelain").trim())
    }

    @Test
    fun `no-verify skips the hooks only when asked`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1") } }
        hook(repo, "pre-commit", "exit 1")
        stageFile(sandbox, repo, "b.txt", "2")
        val rig = Rig(sandbox)
        assertIs<Result.Failure>(rig.commits.commit(repo, CommitDraft("Add b")))
        val id = rig.commits.commit(repo, CommitDraft("Add b", noVerify = true)).getOrThrow()
        assertEquals(head(sandbox, repo), id.hex)
    }

    @Test
    fun `signing with no usable key fails with git's own message and commits nothing`(sandbox: GitSandbox) =
        runBlocking {
            val repo = sandbox.repo { commit("one") { file("a.txt", "1") } }
            sandbox.git(repo, "config", "commit.gpgsign", "true")
            sandbox.git(repo, "config", "gpg.program", repo.resolve("no-such-gpg").toString())
            stageFile(sandbox, repo, "b.txt", "2")
            val before = head(sandbox, repo)
            val result = Rig(sandbox).commits.commit(repo, CommitDraft("Add b"))
            assertIs<Result.Failure>(result)
            val error = assertIs<GitExitException>(result.error)
            assertTrue(error.stderr.isNotBlank())
            assertEquals(before, head(sandbox, repo))
        }

    @Test
    fun `a commit through the recorder is an operation Undo can take back`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1") } }
        stageFile(sandbox, repo, "b.txt", "2")
        val rig = Rig(sandbox)
        val before = head(sandbox, repo)
        rig.commits.commit(repo, CommitDraft("Add b")).getOrThrow()
        val entry = rig.log.entries(repo).getOrThrow().first()
        assertEquals(OperationKind.COMMIT, entry.kind)
        assertEquals(OperationOutcome.SUCCEEDED, entry.outcome)
        assertTrue(entry.argv.containsAll(listOf("git", "commit", "-F", "-")))
        rig.log.undo(repo).getOrThrow()
        assertEquals(before, head(sandbox, repo))
        // The change is still staged, ready to commit again.
        assertEquals("A  b.txt", sandbox.git(repo, "status", "--porcelain").trim())
        rig.log.redo(repo).getOrThrow()
        assertNotEquals(before, head(sandbox, repo))
    }

    // --- selection --------------------------------------------------------------------------------------------

    @Test
    fun `committing a selection takes only what was selected`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo {
            commit("one") {
                file("a.txt", "1\n")
                file("b.txt", "1\n")
            }
        }
        write(repo, "a.txt", "changed a\n")
        write(repo, "b.txt", "changed b\n")
        write(repo, "new.txt", "new\n")
        val rig = Rig(sandbox)
        val selection =
            ChangeSelection(listOf(FileSelection("a.txt", Scope.WholeFile), FileSelection("new.txt", Scope.WholeFile)))
        rig.commits.commit(repo, CommitDraft("Just a"), selection).getOrThrow()
        assertEquals(
            listOf("a.txt", "new.txt"),
            sandbox.git(repo, "show", "--name-only", "--format=", "HEAD").lines().filter { it.isNotBlank() }.sorted(),
        )
        // The rest is where it was: b.txt is still a change in the working tree.
        assertEquals(" M b.txt", sandbox.git(repo, "status", "--porcelain").trimEnd())
        assertEquals("changed b\n", Files.readString(repo.resolve("b.txt")))
    }

    @Test
    fun `committing one hunk leaves the other hunk in the working tree`(sandbox: GitSandbox) = runBlocking {
        val lines = (1..30).joinToString("") { "line $it\n" }
        val repo = sandbox.repo { commit("one") { file("f.txt", lines) } }
        write(repo, "f.txt", lines.replace("line 2\n", "LINE 2\n").replace("line 29\n", "LINE 29\n"))
        val selection = ChangeSelection(listOf(FileSelection("f.txt", Scope.Hunks(setOf(0)))))
        Rig(sandbox).commits.commit(repo, CommitDraft("First hunk"), selection).getOrThrow()
        val committed = sandbox.git(repo, "show", "HEAD:f.txt")
        assertContains(committed, "LINE 2\n")
        assertFalse(committed.contains("LINE 29"))
        // The second hunk is still an unstaged change.
        assertEquals(" M f.txt", sandbox.git(repo, "status", "--porcelain").trimEnd())
        assertContains(Files.readString(repo.resolve("f.txt")), "LINE 29")
    }

    @Test
    fun `a selection leaves what else is staged staged`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo {
            commit("one") {
                file("a.txt", "1\n")
                file("b.txt", "1\n")
            }
        }
        write(repo, "a.txt", "2\n")
        stageFile(sandbox, repo, "b.txt", "2\n")
        val selection = ChangeSelection(listOf(FileSelection("a.txt", Scope.WholeFile)))
        Rig(sandbox).commits.commit(repo, CommitDraft("Just a"), selection).getOrThrow()
        assertEquals("M  b.txt", sandbox.git(repo, "status", "--porcelain").trim())
    }

    @Test
    fun `a failing hook on a selection commit changes neither the branch nor the index`(sandbox: GitSandbox) =
        runBlocking {
            val repo = sandbox.repo { commit("one") { file("a.txt", "1\n") } }
            hook(repo, "pre-commit", "echo nope >&2\nexit 1")
            write(repo, "a.txt", "2\n")
            val before = head(sandbox, repo)
            val selection = ChangeSelection(listOf(FileSelection("a.txt", Scope.WholeFile)))
            assertIs<Result.Failure>(Rig(sandbox).commits.commit(repo, CommitDraft("A"), selection))
            assertEquals(before, head(sandbox, repo))
            assertEquals(" M a.txt", sandbox.git(repo, "status", "--porcelain").trimEnd())
            assertFalse(Files.list(repo.resolve(".git/graft/tmp")).use { it.findAny().isPresent })
        }

    @Test
    fun `a selection that selects nothing is refused`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1\n") } }
        val result = Rig(sandbox).commits.commit(repo, CommitDraft("Nothing"), ChangeSelection(emptyList()))
        val error = (result as Result.Failure).error as GitCommitRefusedException
        assertEquals(CommitProblem.NOTHING_TO_COMMIT, error.problem)
    }

    // --- amend ------------------------------------------------------------------------------------------------

    @Test
    fun `amending the message alone leaves the staged changes staged`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1") } }
        stageFile(sandbox, repo, "b.txt", "2")
        val rig = Rig(sandbox)
        val before = head(sandbox, repo)
        rig.commits.amend(repo, CommitDraft("Better words", "More."), withStaged = false).getOrThrow()
        assertNotEquals(before, head(sandbox, repo))
        assertEquals("Better words\n\nMore.", message(sandbox, repo))
        assertEquals("A  b.txt", sandbox.git(repo, "status", "--porcelain").trim())
    }

    @Test
    fun `amending with staged changes folds them into the commit`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1") } }
        stageFile(sandbox, repo, "b.txt", "2")
        Rig(sandbox).commits.amend(repo, CommitDraft("one, with b")).getOrThrow()
        val tree = sandbox.git(repo, "ls-tree", "--name-only", "HEAD").lines().filter { it.isNotBlank() }
        assertEquals(listOf("a.txt", "b.txt"), tree)
        assertEquals("", sandbox.git(repo, "status", "--porcelain").trim())
        assertEquals("1", sandbox.git(repo, "rev-list", "--count", "HEAD").trim())
    }

    @Test
    fun `amend is recorded and Undo puts the replaced commit back`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1") } }
        val rig = Rig(sandbox)
        val original = head(sandbox, repo)
        rig.commits.amend(repo, CommitDraft("reworded"), withStaged = false).getOrThrow()
        assertEquals(OperationKind.AMEND, rig.log.entries(repo).getOrThrow().first().kind)
        rig.log.undo(repo).getOrThrow()
        assertEquals(original, head(sandbox, repo))
        assertEquals("one", message(sandbox, repo))
    }

    @Test
    fun `amending before the first commit fails with git's message`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo()
        val result = Rig(sandbox).commits.amend(repo, CommitDraft("x"), withStaged = false)
        assertEquals("GIT-R-AMND-EXIT", (result as Result.Failure).error.code)
    }

    @Test
    fun `the previous message is offered split into summary and description`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo {
            commit("one") { file("a.txt", "1") }
            git("commit", "--allow-empty", "-q", "-m", "Second thing", "-m", "Why it was done.\nIn two lines.")
        }
        val rig = Rig(sandbox)
        assertEquals(
            CommitMessageText("Second thing", "Why it was done.\nIn two lines."),
            rig.commits.previousMessage(repo).getOrThrow(),
        )
        assertNull(rig.commits.previousMessage(sandbox.repo("empty")).getOrThrow())
    }

    // --- template, authors ------------------------------------------------------------------------------------

    @Test
    fun `the commit template is read from a relative or an absolute path`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1") } }
        val rig = Rig(sandbox)
        assertNull(rig.commits.template(repo).getOrThrow())
        write(repo, "tpl.txt", "type(scope): \n\nWhy:\n")
        sandbox.git(repo, "config", "commit.template", "tpl.txt")
        assertEquals("type(scope): \n\nWhy:\n", rig.commits.template(repo).getOrThrow())
        sandbox.git(repo, "config", "commit.template", repo.resolve("tpl.txt").toString())
        assertEquals("type(scope): \n\nWhy:\n", rig.commits.template(repo).getOrThrow())
        sandbox.git(repo, "config", "commit.template", "missing.txt")
        assertNull(rig.commits.template(repo).getOrThrow())
    }

    @Test
    fun `recent authors are distinct, newest first, and leave out Graft's own`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1") } }
        listOf("ada@example.org", "ADA@example.org").forEach { email ->
            sandbox.git(repo, "commit", "--allow-empty", "-q", "-m", "x", "--author=Ada <$email>")
        }
        val authors = Rig(sandbox).commits.recentAuthors(repo).getOrThrow()
        assertEquals("ada@example.org", authors.first().email.lowercase())
        assertEquals(authors.size, authors.map { it.email.lowercase() }.distinct().size)
        assertTrue(Rig(sandbox).commits.recentAuthors(sandbox.repo("empty")).getOrThrow().isEmpty())
    }

    // --- pure parts -------------------------------------------------------------------------------------------

    @Test
    fun `a co-author line is Name and an email in angle brackets, nothing else`() {
        assertEquals(CoAuthor("Ada Lovelace", "ada@example.org"), CoAuthor.parse("  Ada Lovelace <ada@example.org> "))
        assertEquals("Ada <a@b.c>", CoAuthor("Ada", "a@b.c").toString())
        listOf("Ada", "<a@b.c>", "Ada <abc>", "Ada <a@b.c> extra", "A\nB <a@b.c>", "Ada <a@b.c><d@e.f>").forEach {
            assertNull(CoAuthor.parse(it), it)
        }
    }

    @Test
    fun `a draft's message is the summary, a blank line, the description`() {
        assertEquals("S", CommitDraft("  S ").message)
        assertEquals("S\n\nD1\nD2", CommitDraft("S", "\nD1\nD2\n").message)
    }

    @Test
    fun `a stored message splits at its first line`() {
        assertEquals(CommitMessageText("S", ""), CommitMessageText.of("S\n"))
        assertEquals(CommitMessageText("S", "B1\n\nB2"), CommitMessageText.of("S\n\nB1\n\nB2\n"))
        assertEquals(CommitMessageText("S", "B"), CommitMessageText.of("S\nB"))
    }

    @Test
    fun `the 50-72 guide reports a long summary and long description lines as data`() {
        val ok = CommitGuide.of("x".repeat(50), "y".repeat(72))
        assertFalse(ok.summaryTooLong)
        assertTrue(ok.longDescriptionLines.isEmpty())
        val long = CommitGuide.of("x".repeat(51), "short\n" + "y".repeat(73) + "\nshort\n" + "z".repeat(80))
        assertTrue(long.summaryTooLong)
        assertEquals(51, long.summaryLength)
        assertEquals(listOf(2, 4), long.longDescriptionLines)
        val raw = CommitGuide.ofMessage("Summary\nno blank line")
        assertTrue(raw.missingBlankLine)
    }
}
