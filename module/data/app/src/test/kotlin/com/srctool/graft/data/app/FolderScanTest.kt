// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.app

import com.srctool.graft.core.configuration.AppFolders
import com.srctool.graft.data.app.di.AppDataModule
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FolderScanTest {
    @TempDir
    lateinit var temp: Path

    private val scan by lazy {
        AppDataModule(AppFolders(temp.resolve("d"), temp.resolve("d"), temp.resolve("c"), temp.resolve("l"))).folderScan
    }

    private val projects by lazy { Files.createDirectories(temp.resolve("Projects")).toRealPath() }

    private fun dir(relative: String): Path = Files.createDirectories(projects.resolve(relative))

    private fun repo(relative: String): Path = dir("$relative/.git").parent

    private fun bare(relative: String): Path = dir(relative).also {
        Files.writeString(it.resolve("HEAD"), "ref: refs/heads/main\n")
        Files.createDirectories(it.resolve("objects"))
        Files.createDirectories(it.resolve("refs"))
    }

    @Test
    fun `finds repositories at any depth up to the limit, sorted`() = runBlocking<Unit> {
        val app = repo("app")
        val lib = repo("group/lib")
        val server = bare("group/server.git")
        val deep = repo("a/b/c/deep")
        repo("a/b/c/d/too-deep")
        Files.writeString(dir("worktree").resolve(".git"), "gitdir: /elsewhere/.git/worktrees/w\n")
        dir("not-a-repo/src")

        assertEquals(
            listOf(deep, app, lib, server, projects.resolve("worktree")),
            scan.scan(projects).getOrThrow(),
        )
        assertEquals(listOf(app, projects.resolve("worktree")), scan.scan(projects, maxDepth = 1).getOrThrow())
    }

    @Test
    fun `a repository's own folders and hidden folders aren't searched`() = runBlocking<Unit> {
        val outer = repo("outer")
        repo("outer/vendor/submodule")
        repo(".cache/hidden")
        assertEquals(listOf(outer), scan.scan(projects).getOrThrow())
    }

    @Test
    fun `a folder that is a repository finds itself`() = runBlocking<Unit> {
        val app = repo("app")
        assertEquals(listOf(app), scan.scan(app).getOrThrow())
    }

    @Test
    fun `symlinks aren't followed`() = runBlocking<Unit> {
        val elsewhere = Files.createDirectories(temp.resolve("elsewhere/repo/.git")).parent
        try {
            Files.createSymbolicLink(projects.resolve("link"), elsewhere)
        } catch (e: IOException) {
            assumeTrue(false, "symlinks aren't allowed here: ${e.message}")
        } catch (e: UnsupportedOperationException) {
            assumeTrue(false, "symlinks aren't supported here: ${e.message}")
        }
        assertEquals(emptyList(), scan.scan(projects).getOrThrow())
    }

    @Test
    fun `a folder that doesn't exist fails`() = runBlocking<Unit> {
        assertIs<Result.Failure>(scan.scan(temp.resolve("nope")))
    }
}
