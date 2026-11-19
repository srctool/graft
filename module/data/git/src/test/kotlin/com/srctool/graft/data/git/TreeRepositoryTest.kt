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
import kotlin.test.assertTrue

/** The files group's source: HEAD's tree, whole names, capped. */
@ExtendWith(GitSandboxExtension::class)
class TreeRepositoryTest {
    private fun trees(sandbox: GitSandbox) = GitDataModule(sandbox.runner()).trees

    @Test
    fun `every file in HEAD is listed with its whole name, and files that aren't committed are not`(
        sandbox: GitSandbox,
    ) = runBlocking<Unit> {
        val repo = sandbox.dir("tree")
        sandbox.git(repo, "init", "-q", "-b", "main")
        Files.createDirectories(repo.resolve("src/auth"))
        Files.writeString(repo.resolve("README.md"), "x\n")
        Files.writeString(repo.resolve("src/auth/Login.kt"), "x\n")
        Files.writeString(repo.resolve("a name with spaces.txt"), "x\n")
        Files.writeString(repo.resolve("ünï.txt"), "x\n")
        sandbox.git(repo, "add", ".")
        sandbox.git(repo, "commit", "-q", "-m", "files")
        Files.writeString(repo.resolve("untracked.txt"), "x\n")
        val list = trees(sandbox).files(repo).getOrThrow()
        assertEquals(
            listOf("README.md", "a name with spaces.txt", "src/auth/Login.kt", "ünï.txt"),
            list.paths.sorted(),
        )
        assertEquals(false, list.truncated)
    }

    @Test
    fun `the list stops at the limit and says so`(sandbox: GitSandbox) = runBlocking<Unit> {
        val repo = sandbox.dir("many")
        sandbox.git(repo, "init", "-q", "-b", "main")
        repeat(7) { Files.writeString(repo.resolve("f$it.txt"), "x\n") }
        sandbox.git(repo, "add", ".")
        sandbox.git(repo, "commit", "-q", "-m", "files")
        val list = trees(sandbox).files(repo, limit = 5).getOrThrow()
        assertEquals(5, list.paths.size)
        assertTrue(list.truncated)
        assertEquals(false, trees(sandbox).files(repo, limit = 7).getOrThrow().truncated)
    }

    @Test
    fun `a repository with no commits has no files, and that isn't a failure`(sandbox: GitSandbox) = runBlocking<Unit> {
        val repo = sandbox.dir("empty")
        sandbox.git(repo, "init", "-q", "-b", "main")
        val list = trees(sandbox).files(repo).getOrThrow()
        assertEquals(emptyList(), list.paths)
    }
}
