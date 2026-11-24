// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.GitExitException
import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.graft.data.git.api.models.ChangeSelection
import com.srctool.graft.data.git.api.models.CommitDraft
import com.srctool.graft.data.git.api.models.FileBlocks
import com.srctool.graft.data.git.api.models.FileSelection
import com.srctool.graft.data.git.api.models.FileSelection.Scope
import com.srctool.graft.data.git.api.models.HunkAnchor
import com.srctool.graft.data.git.api.models.Reanchoring
import com.srctool.graft.data.git.api.models.Reanchoring.How
import com.srctool.graft.data.git.api.models.StoredAnchor
import com.srctool.graft.data.git.api.models.WorkingBlocks
import com.srctool.graft.data.git.api.models.WorkingHunk
import com.srctool.graft.data.git.api.repositories.BlockSource
import com.srctool.graft.data.git.implementation.repositories.GitChangeBlocksRepository
import com.srctool.graft.data.git.implementation.repositories.GitCommittingRepository
import com.srctool.graft.data.git.implementation.repositories.GitLocalHistoryRepository
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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Hunks as changelists see them: read from a real repository, matched again after the file changes, and committed by
 * list. The matching itself is pure and is tested on the same repositories, which is where edits really come from.
 */
@ExtendWith(GitSandboxExtension::class)
class ChangeBlocksTest {
    private class Rig(sandbox: GitSandbox) {
        val runner = sandbox.runner()
        private val recorder = MutationRecorder(runner, OpLogStore())
        val staging = GitStagingRepository(runner, GitLocalHistoryRepository(runner), recorder)
        val commits = GitCommittingRepository(runner, staging, recorder)
        val blocks = GitChangeBlocksRepository(runner)
    }

    /** Forty lines, so changes near the top and the bottom are separate hunks. */
    private val base = (1..40).joinToString("\n") { "line $it" } + "\n"

    private fun lines(vararg edits: Pair<Int, String?>): String {
        val map = edits.toMap()
        return (1..40).mapNotNull { n -> if (n in map) map[n] else "line $n" }.joinToString("\n") + "\n"
    }

    private fun write(repo: Path, file: String, text: String) = Files.writeString(repo.resolve(file), text)

    private fun repoWithFile(sandbox: GitSandbox): Path = sandbox.repo { commit("one") { file("a.txt", base) } }

    private suspend fun Rig.read(repo: Path, source: BlockSource = BlockSource.WORKTREE) =
        blocks.read(repo, source).getOrThrow()

    private fun WorkingBlocks.file(path: String): FileBlocks = files.first { it.path == path }

    /** Every hunk of [blocks] stored under [tag]. */
    private fun stored(blocks: WorkingBlocks, tag: String, pick: (Int) -> Boolean = { true }) =
        blocks.files.flatMap { f ->
            f.hunks.filter { pick(it.id) }.map { StoredAnchor(tag, f.path, it.anchor) }
        }

    // --- reading ----------------------------------------------------------------------------------------------

    @Test
    fun `a modified file gives one hunk per change and other paths are whole`(sandbox: GitSandbox) = runBlocking {
        val repo =
            sandbox.repo {
                commit("one") {
                    file("a.txt", base)
                    file("gone.txt", "bye\n")
                    file("pic.bin", "x")
                }
            }
        write(repo, "a.txt", lines(5 to "five", 35 to "thirty-five"))
        write(repo, "new.txt", "new\n")
        Files.delete(repo.resolve("gone.txt"))
        Files.write(repo.resolve("pic.bin"), byteArrayOf(0, 1, 2, 0))
        val blocks = Rig(sandbox).read(repo)
        assertEquals(listOf("a.txt"), blocks.files.map { it.path })
        assertEquals(listOf(0, 1), blocks.file("a.txt").hunks.map { it.id })
        assertEquals(listOf(1, 1), blocks.file("a.txt").hunks.map { it.additions })
        val whole = blocks.whole.associate { it.path to it.untracked }
        // A binary file is "modified" but has no hunks to pick from, so it moves whole.
        assertEquals(mapOf("gone.txt" to false, "new.txt" to true, "pic.bin" to false), whole)
    }

    @Test
    fun `before the first commit every path is whole`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo()
        write(repo, "a.txt", "1\n")
        val blocks = Rig(sandbox).read(repo)
        assertEquals(emptyList(), blocks.files)
        assertEquals(listOf("a.txt"), blocks.whole.map { it.path })
    }

    @Test
    fun `the index source reads only what is staged`(sandbox: GitSandbox) = runBlocking {
        val repo = repoWithFile(sandbox)
        write(repo, "a.txt", lines(5 to "five"))
        sandbox.git(repo, "add", "a.txt")
        write(repo, "a.txt", lines(5 to "five", 35 to "thirty-five"))
        write(repo, "b.txt", "b\n")
        val rig = Rig(sandbox)
        assertEquals(1, rig.read(repo, BlockSource.INDEX).file("a.txt").hunks.size)
        assertEquals(emptyList(), rig.read(repo, BlockSource.INDEX).whole)
        assertEquals(2, rig.read(repo).file("a.txt").hunks.size)
    }

    // --- the selection of a list ------------------------------------------------------------------------------

    @Test
    fun `a selection of every hunk of a file is the whole file`(sandbox: GitSandbox) = runBlocking {
        val repo = repoWithFile(sandbox)
        write(repo, "a.txt", lines(5 to "five", 35 to "thirty-five"))
        write(repo, "new.txt", "n\n")
        val blocks = Rig(sandbox).read(repo)
        val all = blocks.selection { _, _ -> true }
        assertEquals(
            listOf(FileSelection("a.txt", Scope.WholeFile), FileSelection("new.txt", Scope.WholeFile)),
            all.files,
        )
        val one = blocks.selection { path, hunk -> path == "a.txt" && hunk == 1 }
        assertEquals(listOf(FileSelection("a.txt", Scope.Hunks(setOf(1)))), one.files)
    }

    // --- matching again ----------------------------------------------------------------------------------------

    @Test
    fun `edits above a hunk leave it exactly where it was`(sandbox: GitSandbox) = runBlocking {
        val repo = repoWithFile(sandbox)
        val rig = Rig(sandbox)
        write(repo, "a.txt", lines(20 to "twenty"))
        val before = rig.read(repo)
        // Lines added at the top move the hunk in the file, not in the old side the anchor is made of.
        write(repo, "a.txt", "top 1\ntop 2\ntop 3\n" + lines(20 to "twenty"))
        val after = rig.read(repo)
        val result = Reanchoring.of(stored(before, "L1"), after)
        assertEquals(listOf("L1" to How.EXACT), result.kept.filter { it.hunk.id == 1 }.map { it.stored.tag to it.how })
        // The new lines at the top are a hunk of their own, with no owner.
        assertEquals(1, result.fresh.size)
        assertEquals(emptyList(), result.dropped)
    }

    @Test
    fun `an edit inside a hunk keeps it with its list`(sandbox: GitSandbox) = runBlocking {
        val repo = repoWithFile(sandbox)
        val rig = Rig(sandbox)
        write(repo, "a.txt", lines(5 to "five", 35 to "thirty-five"))
        val before = rig.read(repo)
        write(repo, "a.txt", lines(5 to "five, edited again", 35 to "thirty-five"))
        val result = Reanchoring.of(
            stored(before, "L1", pick = {
                it == 0
            }) + stored(before, "L2", { it == 1 }),
            rig.read(repo),
        )
        val byId = result.kept.associateBy { it.hunk.id }
        assertEquals("L1" to How.EDITED, byId.getValue(0).let { it.stored.tag to it.how })
        assertEquals("L2" to How.EXACT, byId.getValue(1).let { it.stored.tag to it.how })
        assertEquals(emptyList(), result.fresh)
        assertEquals(emptyList(), result.replaced)
        assertEquals(emptyList(), result.dropped)
    }

    @Test
    fun `a change next to a hunk merges into it and the list keeps both`(sandbox: GitSandbox) = runBlocking {
        val repo = repoWithFile(sandbox)
        val rig = Rig(sandbox)
        write(repo, "a.txt", lines(20 to "twenty"))
        val before = rig.read(repo)
        // The line after it changes too, so the two changes are one hunk now, with another old side.
        write(repo, "a.txt", lines(20 to "twenty", 21 to "twenty-one"))
        val after = rig.read(repo)
        assertEquals(1, after.file("a.txt").hunks.size)
        val result = Reanchoring.of(stored(before, "L1"), after)
        assertEquals(listOf("L1" to How.MERGED), result.kept.map { it.stored.tag to it.how })
    }

    @Test
    fun `a hunk that is gone is dropped and a new change has no owner`(sandbox: GitSandbox) = runBlocking {
        val repo = repoWithFile(sandbox)
        val rig = Rig(sandbox)
        write(repo, "a.txt", lines(5 to "five", 35 to "thirty-five"))
        val before = rig.read(repo)
        // The first change is undone; a different one appears far from it.
        write(repo, "a.txt", lines(35 to "thirty-five", 20 to "twenty"))
        val result = Reanchoring.of(stored(before, "L1"), rig.read(repo))
        assertEquals(listOf("L1"), result.kept.map { it.stored.tag })
        assertEquals(1, result.dropped.size)
        assertEquals(1, result.fresh.size)
        assertEquals(emptyList(), result.replaced)
    }

    @Test
    fun `a deleted file drops its hunks`(sandbox: GitSandbox) = runBlocking {
        val repo = repoWithFile(sandbox)
        val rig = Rig(sandbox)
        write(repo, "a.txt", lines(5 to "five"))
        val before = rig.read(repo)
        Files.delete(repo.resolve("a.txt"))
        val after = rig.read(repo)
        assertEquals(listOf("a.txt"), after.whole.map { it.path })
        val result = Reanchoring.of(stored(before, "L1"), after)
        assertEquals(emptyList(), result.kept)
        assertEquals(1, result.dropped.size)
    }

    @Test
    fun `an assignment follows a rename`(sandbox: GitSandbox) = runBlocking {
        val repo = repoWithFile(sandbox)
        val rig = Rig(sandbox)
        write(repo, "a.txt", lines(5 to "five", 35 to "thirty-five"))
        val before = rig.read(repo)
        sandbox.git(repo, "mv", "a.txt", "renamed.txt")
        val after = rig.read(repo)
        assertEquals("a.txt", after.file("renamed.txt").originalPath)
        val result = Reanchoring.of(stored(before, "L1"), after)
        assertEquals(listOf("renamed.txt", "renamed.txt"), result.kept.map { it.path })
        assertEquals(listOf("L1", "L1"), result.kept.map { it.stored.tag })
    }

    @Test
    fun `a hunk is found again after HEAD moves under it`(sandbox: GitSandbox) = runBlocking {
        val repo = repoWithFile(sandbox)
        val rig = Rig(sandbox)
        // Another branch adds three lines at the top; switching to it carries the working change along.
        sandbox.git(repo, "switch", "-c", "other")
        write(repo, "a.txt", "top 1\ntop 2\ntop 3\n" + base)
        sandbox.git(repo, "commit", "-am", "top")
        sandbox.git(repo, "switch", "-")
        write(repo, "a.txt", lines(35 to "thirty-five"))
        val before = rig.read(repo)
        // `-m` merges the working change into the other branch, which `switch` alone refuses for a file both touch.
        sandbox.git(repo, "switch", "-m", "other")
        val after = rig.read(repo)
        assertEquals(
            before.file("a.txt").hunks.single().anchor.oldStart + 3,
            after.file("a.txt").hunks.single().anchor.oldStart,
        )
        val result = Reanchoring.of(stored(before, "L1"), after)
        assertEquals(listOf("L1" to How.MOVED), result.kept.map { it.stored.tag to it.how })
        assertEquals(emptyList(), result.dropped)
    }

    @Test
    fun `a hunk rewritten near where it was is replaced, not kept`() {
        fun anchor(start: Int, tag: String) = HunkAnchor(start, 7, "change-$tag", "removed-$tag", "base-$tag", 1)
        val old = StoredAnchor("L1", "a.txt", anchor(10, "old"))
        // Nothing about it matches and its old side does not touch the old one, but it is a few lines away.
        val current =
            WorkingBlocks(
                listOf(FileBlocks("a.txt", null, listOf(WorkingHunk(0, anchor(19, "new"), 2, 1)))),
                emptyList(),
            )
        val result = Reanchoring.of(listOf(old), current)
        assertEquals(listOf(old), result.replaced.map { it.stored })
        assertEquals(emptyList(), result.kept)
        assertEquals(emptyList(), result.dropped)
        // The same hunk far away is simply a new change, and the old one is gone.
        val far =
            WorkingBlocks(
                listOf(FileBlocks("a.txt", null, listOf(WorkingHunk(0, anchor(60, "new"), 2, 1)))),
                emptyList(),
            )
        val apart = Reanchoring.of(listOf(old), far)
        assertEquals(1, apart.fresh.size)
        assertEquals(listOf(old), apart.dropped)
    }

    @Test
    fun `two identical hunks are matched one to one`(sandbox: GitSandbox) = runBlocking {
        val repo =
            sandbox.repo { commit("one") { file("a.txt", (1..40).joinToString("\n") { "same" } + "\n") } }
        val rig = Rig(sandbox)
        write(repo, "a.txt", (1..40).joinToString("\n") { if (it == 5 || it == 35) "changed" else "same" } + "\n")
        val blocks = rig.read(repo)
        val result = Reanchoring.of(stored(blocks, "A", { it == 0 }) + stored(blocks, "B", { it == 1 }), blocks)
        assertEquals(mapOf(0 to "A", 1 to "B"), result.kept.associate { it.hunk.id to it.stored.tag })
    }

    // --- committing and the staged set -------------------------------------------------------------------------

    @Test
    fun `committing one list leaves the other lists changes in the working tree`(sandbox: GitSandbox) = runBlocking {
        val repo = repoWithFile(sandbox)
        val rig = Rig(sandbox)
        write(repo, "a.txt", lines(5 to "five", 35 to "thirty-five"))
        write(repo, "notes.txt", "notes\n")
        val blocks = rig.read(repo)
        // List "fix" owns the first hunk; list "debug" owns the second hunk and the new file.
        val fix = blocks.selection { path, hunk -> path == "a.txt" && hunk == 0 }
        rig.commits.commit(repo, CommitDraft("Fix five"), fix).getOrThrow()
        assertEquals("a.txt\n", sandbox.git(repo, "show", "--name-only", "--format=", "HEAD"))
        assertTrue(sandbox.git(repo, "show", "HEAD").contains("+five"))
        assertTrue(!sandbox.git(repo, "show", "HEAD").contains("thirty-five"))
        val after = rig.read(repo)
        assertEquals(1, after.file("a.txt").hunks.size)
        assertEquals(listOf("notes.txt"), after.whole.map { it.path })
        // The committed hunk is gone from the old tracking; the remaining one is found again exactly.
        val result = Reanchoring.of(stored(blocks, "debug", { it == 1 }) + stored(blocks, "fix", { it == 0 }), after)
        assertEquals(listOf("debug"), result.kept.map { it.stored.tag })
        assertEquals(listOf("fix"), result.dropped.map { it.tag })
    }

    @Test
    fun `a failing hook leaves the working tree and the hunks as they were`(sandbox: GitSandbox) = runBlocking {
        val repo = repoWithFile(sandbox)
        val rig = Rig(sandbox)
        write(repo, "a.txt", lines(5 to "five", 35 to "thirty-five"))
        val hooks = repo.resolve(".git/hooks").also { Files.createDirectories(it) }
        Files.writeString(hooks.resolve("pre-commit"), "#!/bin/sh\necho no >&2\nexit 1\n")
        hooks.resolve("pre-commit").toFile().setExecutable(true)
        val before = rig.read(repo)
        val failed = rig.commits.commit(repo, CommitDraft("Nope"), before.selection { _, h -> h == 0 })
        assertIs<Result.Failure>(failed)
        assertIs<GitExitException>(failed.error)
        assertEquals(before, rig.read(repo))
        assertEquals("one", sandbox.git(repo, "log", "-1", "--format=%s").trim())
    }

    @Test
    fun `staged changes become hunks again once they are unstaged`(sandbox: GitSandbox) = runBlocking {
        val repo = repoWithFile(sandbox)
        val rig = Rig(sandbox)
        write(repo, "a.txt", lines(5 to "five", 35 to "thirty-five"))
        rig.staging.stage(repo, ChangeSelection(listOf(FileSelection("a.txt", Scope.Hunks(setOf(0)))))).getOrThrow()
        val staged = rig.read(repo, BlockSource.INDEX)
        val all = rig.read(repo)
        assertEquals(1, staged.file("a.txt").hunks.size)
        // The staged hunk is one of the two the working tree has, found by the same anchors.
        val match = Reanchoring.of(stored(staged, "Staged"), all)
        assertEquals(listOf(How.EXACT), match.kept.map { it.how })
        assertEquals(0, match.kept.single().hunk.id)
        // Moving the staged set back to the working tree (the switch to changelists) leaves one diff to read.
        rig.staging.unstage(repo, ChangeSelection(listOf(FileSelection("a.txt", Scope.WholeFile)))).getOrThrow()
        assertEquals(emptyList(), rig.read(repo, BlockSource.INDEX).files)
        assertEquals(all, rig.read(repo))
    }

    @Test
    fun `staging a lists hunks is the switch back to the staging area`(sandbox: GitSandbox) = runBlocking {
        val repo = repoWithFile(sandbox)
        val rig = Rig(sandbox)
        write(repo, "a.txt", lines(5 to "five", 35 to "thirty-five"))
        val blocks = rig.read(repo)
        rig.staging.stage(repo, blocks.selection { _, h -> h == 1 }).getOrThrow()
        val staged = rig.read(repo, BlockSource.INDEX)
        assertEquals(blocks.file("a.txt").hunks[1].anchor.change, staged.file("a.txt").hunks.single().anchor.change)
        assertEquals(" M a.txt".length, sandbox.git(repo, "status", "--porcelain").trimEnd().length)
    }

    @Test
    fun `discarding a list takes a snapshot first and leaves the other lists alone`(sandbox: GitSandbox) = runBlocking {
        val repo = repoWithFile(sandbox)
        val rig = Rig(sandbox)
        val history = GitLocalHistoryRepository(rig.runner)
        write(repo, "a.txt", lines(5 to "five", 35 to "thirty-five"))
        write(repo, "scratch.txt", "scratch\n")
        val blocks = rig.read(repo)
        // The list "debug" owns the first hunk and the new file.
        val debug = blocks.selection { path, hunk -> (path == "a.txt" && hunk == 0) || path == "scratch.txt" }
        rig.staging.discard(repo, debug).getOrThrow()
        assertEquals(lines(35 to "thirty-five"), Files.readString(repo.resolve("a.txt")))
        assertTrue(!Files.exists(repo.resolve("scratch.txt")))
        val snapshots = history.snapshots(repo).getOrThrow()
        assertEquals("before discard", snapshots.first().label)
        // What was thrown away is in the snapshot.
        val versions = history.versions(repo, "scratch.txt").getOrThrow()
        assertTrue(versions.any { it.blob != null })
    }
}
