// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.graft.data.git.implementation.repositories.GitLocalHistoryRepository
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How long a snapshot of a 100,000-file tree takes (Task 4's "done when"). Building the tree takes minutes on a
 * slow disk, so this runs only when `GRAFT_BENCHMARK` is set; the numbers go in the ledger.
 */
@ExtendWith(GitSandboxExtension::class)
@Timeout(value = 20, unit = TimeUnit.MINUTES)
class LocalHistoryBenchmarkTest {
    @Test
    fun `a snapshot of a hundred thousand files`(sandbox: GitSandbox) = runBlocking {
        assumeTrue(System.getenv("GRAFT_BENCHMARK") != null, "set GRAFT_BENCHMARK to run")
        val repo = sandbox.repo()
        repeat(1_000) { dir ->
            val folder = repo.resolve("d$dir")
            Files.createDirectories(folder)
            repeat(100) { Files.writeString(folder.resolve("f$it.txt"), "file $dir/$it\n") }
        }
        sandbox.git(repo, "add", "-A")
        sandbox.git(repo, "commit", "-q", "-m", "all")
        val history = GitLocalHistoryRepository(sandbox.runner())

        fun timed(label: String, block: () -> Unit): Long {
            val start = System.nanoTime()
            block()
            return ((System.nanoTime() - start) / 1_000_000).also {
                println("snapshot of 100,000 files, $label: $it ms")
            }
        }
        // Edit 50 files and add 50, as an hour of work would.
        repeat(50) { Files.writeString(repo.resolve("d$it/f0.txt"), "edited\n") }
        repeat(50) { Files.writeString(repo.resolve("new$it.txt"), "new\n") }
        val firstMs = timed("100 files changed") { runBlocking { history.snapshot(repo).getOrThrow() } }
        val sameMs = timed("nothing changed") { runBlocking { history.snapshot(repo).getOrThrow() } }
        Files.writeString(repo.resolve("d7/f1.txt"), "one more\n")
        val oneMs = timed("one file changed") { runBlocking { history.snapshot(repo).getOrThrow() } }
        assertTrue(firstMs < 120_000 && sameMs < 120_000 && oneMs < 120_000)
    }
}
