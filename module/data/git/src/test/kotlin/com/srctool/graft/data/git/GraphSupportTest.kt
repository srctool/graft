// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.graft.data.git.di.GitDataModule
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the graph screen reads besides the log: the changed-file count of its WIP row, and the commit-graph file. */
@ExtendWith(GitSandboxExtension::class)
class GraphSupportTest {
    @Test
    fun `the summary counts changed paths, a rename once`(sandbox: GitSandbox) = runBlocking {
        val repo =
            sandbox.repo("wip") {
                commit("init") {
                    file("a.txt", "a\n")
                    file("b.txt", "b\n")
                    file("old.txt", "a file long enough for git to see the rename\n".repeat(4))
                }
            }
        val access = GitDataModule(sandbox.runner()).access
        assertEquals(0, access.summary(repo).getOrThrow().changedFiles)

        Files.writeString(repo.resolve("a.txt"), "changed\n")
        Files.writeString(repo.resolve("new.txt"), "untracked\n")
        sandbox.git(repo, "mv", "old.txt", "renamed.txt")
        val summary = access.summary(repo).getOrThrow()
        assertTrue(summary.dirty)
        // a.txt modified, renamed.txt (with its original path as a second record), new.txt untracked.
        assertEquals(3, summary.changedFiles)
    }

    @Test
    fun `a commit-graph file is found once it's written`(sandbox: GitSandbox) = runBlocking {
        val repo =
            sandbox.repo("history") {
                commit("one") { file("a.txt", "1\n") }
                commit("two") { file("a.txt", "2\n") }
            }
        val commits = GitDataModule(sandbox.runner()).commits
        assertFalse(commits.hasCommitGraph(repo).getOrThrow())
        commits.writeCommitGraph(repo).getOrThrow()
        assertTrue(commits.hasCommitGraph(repo).getOrThrow())
        // Asked from a folder inside the repository, git's paths still lead to the file.
        Files.createDirectories(repo.resolve("sub"))
        assertTrue(commits.hasCommitGraph(repo.resolve("sub")).getOrThrow())
    }
}
