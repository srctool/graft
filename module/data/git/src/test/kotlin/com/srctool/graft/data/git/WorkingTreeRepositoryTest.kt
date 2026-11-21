// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.graft.data.git.api.models.ChangeStatus
import com.srctool.graft.data.git.api.models.ConflictKind
import com.srctool.graft.data.git.api.models.WorkingTree
import com.srctool.graft.data.git.implementation.repositories.GitWorkingTreeRepository
import com.srctool.graft.data.git.implementation.source.local.WorkingTreeParser
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The working tree's status: parsed from output recorded from real repositories (so these run the same everywhere),
 * and against a real repository in the sandbox.
 */
@ExtendWith(GitSandboxExtension::class)
class WorkingTreeRepositoryTest {
    private fun recorded(case: String): WorkingTree = WorkingTreeParser.parse(Fixtures.read("worktree", case))

    private fun WorkingTree.byPath() = entries.associateBy { it.path }

    @Test
    fun `a mixed working tree reads as every kind of change`() {
        val tree = recorded("mixed")
        val by = tree.byPath()
        assertEquals(ChangeStatus.MODIFIED to null, by.getValue("staged.txt").let { it.index to it.worktree })
        assertEquals(null to ChangeStatus.MODIFIED, by.getValue("unstaged.txt").let { it.index to it.worktree })
        assertEquals(
            ChangeStatus.MODIFIED to ChangeStatus.MODIFIED,
            by.getValue("both.txt").let { it.index to it.worktree },
        )
        assertEquals(ChangeStatus.DELETED, by.getValue("gone staged.txt").index)
        assertEquals(ChangeStatus.DELETED, by.getValue("gone unstaged.txt").worktree)
        assertEquals(ChangeStatus.ADDED, by.getValue("added.txt").index)
        val rename = by.getValue("new name.txt")
        assertEquals(ChangeStatus.RENAMED, rename.index)
        assertEquals("old name.txt", rename.originalPath)
        // Untracked files are listed one by one, with their folder's other files, and ignored ones are left out.
        assertEquals(
            listOf("fresh/one.txt", "fresh/two.txt", "untracked file.txt"),
            tree.untracked.map { it.path }.sorted(),
        )
        assertTrue(tree.ignored.isEmpty())
    }

    @Test
    fun `an executable bit changed in the index, or only on disk, is a mode change on that side`() {
        val by = recorded("mode").byPath()
        val staged = by.getValue("staged.sh")
        assertEquals(ChangeStatus.MODIFIED to null, staged.index to staged.worktree)
        assertTrue(staged.modes!!.stagedChange)
        assertFalse(staged.modes!!.unstagedChange)
        val unstaged = by.getValue("unstaged.sh")
        assertEquals(null to ChangeStatus.MODIFIED, unstaged.index to unstaged.worktree)
        assertTrue(unstaged.modes!!.unstagedChange)
        assertFalse(unstaged.modes!!.stagedChange)
    }

    @Test
    fun `asking for ignored paths lists them`() {
        val ignored = recorded("mixed-ignored").ignored.map { it.path }.sorted()
        // Untracked files are listed one by one, so an ignored folder shows by the files in it.
        assertEquals(listOf("build/out.bin", "debug.log"), ignored)
    }

    @Test
    fun `an unfinished merge lists each conflict with how each side changed`() {
        val by = recorded("conflicts").byPath()
        assertEquals(ConflictKind.BOTH_MODIFIED, by.getValue("both.txt").conflict)
        assertEquals(ConflictKind.BOTH_ADDED, by.getValue("added.txt").conflict)
        assertEquals(ConflictKind.DELETED_BY_US, by.getValue("deleted.txt").conflict)
        assertEquals(3, recorded("conflicts").conflicted.size)
    }

    @Test
    fun `a submodule moved on, dirtied and given a new file says all three`() {
        val sub = recorded("submodule").byPath().getValue("sub").submodule
        assertEquals(true, sub?.commitChanged)
        assertEquals(true, sub?.trackedModified)
        assertEquals(true, sub?.untracked)
    }

    @Test
    fun `an unborn branch shows its staged files as added`() {
        val entry = recorded("unborn").entries.single()
        assertEquals("first.txt", entry.path)
        assertEquals(ChangeStatus.ADDED, entry.index)
    }

    @Test
    fun `a clean working tree has no changes`() {
        assertTrue(recorded("clean").entries.isEmpty())
    }

    @Test
    fun `a real repository reads the same as its recording`(sandbox: GitSandbox) = runBlocking {
        val repos = WorkingTreeRepos.build(sandbox)
        val repository = GitWorkingTreeRepository(sandbox.runner())
        repos.forEach { (case, repo) ->
            val live = repository.status(repo).getOrThrow()
            assertEquals(recorded(case), live, case)
        }
        val ignored = repository.status(repos.getValue("mixed"), includeIgnored = true).getOrThrow()
        assertEquals(recorded("mixed-ignored"), ignored)
    }

    @Test
    fun `status leaves the index and the files as they were`(sandbox: GitSandbox) = runBlocking {
        val repo = WorkingTreeRepos.build(sandbox).getValue("mixed")
        val index = repo.resolve(".git/index")
        val before = Files.readAllBytes(index)
        GitWorkingTreeRepository(sandbox.runner()).status(repo).getOrThrow()
        assertEquals(before.toList(), Files.readAllBytes(index).toList())
    }

    @Test
    fun `a folder that is not a repository is a failure`(sandbox: GitSandbox) = runBlocking {
        val result = GitWorkingTreeRepository(sandbox.runner()).status(sandbox.dir("plain"))
        assertIs<Result.Failure>(result)
        assertEquals("GIT-R-STAT-NF", result.error.code)
    }

    @Test
    fun `changes reads the status now, and again for each refresh`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1") } }
        val repository = GitWorkingTreeRepository(sandbox.runner())
        val refreshes = MutableSharedFlow<Unit>()
        val seen = mutableListOf<WorkingTree>()
        val collecting =
            launch { repository.changes(repo, refreshes).take(2).collect { seen += it.getOrThrow() } }
        // The first emission is the status now; wait until it has run before the file changes.
        while (seen.isEmpty() && !collecting.isCompleted) kotlinx.coroutines.delay(20)
        Files.writeString(repo.resolve("a.txt"), "2")
        refreshes.emit(Unit)
        collecting.join()
        assertEquals(listOf(0, 1), seen.map { it.entries.size })
    }

    @Test
    fun `changes with no refreshes is one status`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1") } }
        val first = GitWorkingTreeRepository(sandbox.runner()).changes(repo, emptyFlow()).first()
        assertTrue(first.getOrThrow().entries.isEmpty())
    }
}
