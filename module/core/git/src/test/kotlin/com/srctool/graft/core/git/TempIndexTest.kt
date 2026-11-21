// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.graft.core.git.testing.RawGitCommand
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The temporary index (Git Engine): trees built without ever touching the user's index. */
@ExtendWith(GitSandboxExtension::class)
class TempIndexTest {
    private fun runner(sandbox: GitSandbox) = sandbox.runner()

    private fun gitDir(repo: Path): Path = repo.resolve(".git")

    @Test
    fun `a tree built in a temp index leaves the user's index byte for byte as it was`(sandbox: GitSandbox) =
        runBlocking {
            val repo = sandbox.repo { commit("one") { file("a.txt", "a\n") } }
            // A staged change and an untracked file the user has; neither may end up in, or leave, the temp tree.
            Files.writeString(repo.resolve("a.txt"), "changed\n")
            sandbox.git(repo, "add", "a.txt")
            Files.writeString(repo.resolve("new.txt"), "new\n")
            val before = Files.readAllBytes(gitDir(repo).resolve("index"))

            val tree =
                TempIndex.scope(gitDir(repo)) { index ->
                    val git = runner(sandbox)
                    git.run(RawGitCommand(listOf("read-tree", "HEAD"), environment = index.environment), repo)
                    git.run(RawGitCommand(listOf("add", "--all"), environment = index.environment), repo)
                    git.run(RawGitCommand(listOf("write-tree"), environment = index.environment), repo)
                }.getOrThrow().trim()

            assertEquals(before.toList(), Files.readAllBytes(gitDir(repo).resolve("index")).toList())
            // The temp tree holds the working tree's content: the changed file and the untracked one.
            assertEquals("changed", sandbox.git(repo, "show", "$tree:a.txt").trim())
            assertEquals("new", sandbox.git(repo, "show", "$tree:new.txt").trim())
        }

    @Test
    fun `the temp index lives under the git dir and is gone after, with its lock`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "a") } }
        var seen: Path? = null
        TempIndex.scope(gitDir(repo)) { index ->
            seen = index.file
            assertTrue(index.file.startsWith(gitDir(repo)))
            assertEquals(index.file.toString(), index.environment["GIT_INDEX_FILE"])
            runner(sandbox).run(RawGitCommand(listOf("read-tree", "HEAD"), environment = index.environment), repo)
            assertTrue(Files.exists(index.file))
            Result.Success(Unit)
        }
        assertFalse(Files.exists(seen!!))
        assertFalse(Files.exists(Path.of("$seen.lock")))
    }

    @Test
    fun `a failure inside the scope cleans up and is returned as it was`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "a") } }
        var seen: Path? = null
        val result =
            TempIndex.scope(gitDir(repo)) { index ->
                seen = index.file
                runner(sandbox).run(
                    RawGitCommand(listOf("read-tree", "no-such-tree"), environment = index.environment),
                    repo,
                )
            }
        assertIs<Result.Failure>(result)
        assertFalse(Files.exists(seen!!))
    }

    @Test
    fun `an exception or a cancellation inside the scope cleans up and propagates`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "a") } }
        var seen: Path? = null
        assertFailsWith<CancellationException> {
            TempIndex.scope<Unit>(gitDir(repo)) { index ->
                seen = index.file
                Files.createFile(index.file)
                throw CancellationException("stop")
            }
        }
        assertFalse(Files.exists(seen!!))
    }

    @Test
    fun `two scopes at once get different index files`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "a") } }
        val outer =
            TempIndex.scope(gitDir(repo)) { a ->
                TempIndex.scope(gitDir(repo)) { b ->
                    Result.Success(a.file != b.file)
                }.getOrThrow().let(Result<Boolean>::Success)
            }
        assertTrue(outer.getOrThrow())
    }

    @Test
    fun `an unwritable git dir is a failure result, not an exception`(sandbox: GitSandbox) = runBlocking {
        val blocker = sandbox.root.resolve("not-a-folder").also { Files.writeString(it, "file") }
        val result = TempIndex.scope(blocker.resolve(".git")) { Result.Success(Unit) }
        assertIs<Result.Failure>(result)
        assertTrue(result.error is GitException, result.error.toString())
    }
}
