// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.GitException
import com.srctool.graft.core.git.GitRunnerConfig
import com.srctool.graft.core.git.GitUnsafeOwnerException
import com.srctool.graft.core.git.ProcessGitRunner
import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.graft.data.git.api.models.CloneProgress
import com.srctool.graft.data.git.api.models.CloneRequest
import com.srctool.graft.data.git.api.models.Head
import com.srctool.graft.data.git.api.repositories.RepositoryAccessRepository
import com.srctool.graft.data.git.di.GitDataModule
import com.srctool.sinew.models.errorOrNull
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Opening, summarizing, initializing and cloning real repositories in the sandbox, on the latest Git and on 2.38. */
@ExtendWith(GitSandboxExtension::class)
class RepositoryAccessRepositoryTest {
    private fun GitSandbox.access(): RepositoryAccessRepository = GitDataModule(runner()).access

    private fun GitSandbox.simple(name: String = "app"): Path = repo(name) {
        commit("one") { file("src/a.txt", "1") }
        commit("two") { file("src/a.txt", "2") }
    }

    private fun Path.real(): Path = toRealPath()

    @Test
    fun `a subfolder opens its repository's root`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.simple()
        val location = sandbox.access().locate(repo.resolve("src")).getOrThrow()
        assertEquals(repo.real(), location.workTree)
        assertEquals(repo.resolve(".git").real(), location.gitDir)
        assertEquals(location.gitDir, location.commonDir)
        assertEquals(false, location.isBare)
    }

    @Test
    fun `the git folder itself opens its repository`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.simple()
        assertEquals(repo.real(), sandbox.access().locate(repo.resolve(".git")).getOrThrow().workTree)
    }

    @Test
    fun `a symlinked path resolves to the same repository`(sandbox: GitSandbox) = runBlocking {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "symlinks need a privilege on Windows")
        val repo = sandbox.simple()
        val link = Files.createSymbolicLink(sandbox.root.resolve("link"), repo)
        val access = sandbox.access()
        assertEquals(access.locate(repo).getOrThrow(), access.locate(link).getOrThrow())
    }

    @Test
    fun `a folder outside any repository is not found`(sandbox: GitSandbox) = runBlocking {
        val error = sandbox.access().locate(sandbox.dir("plain")).errorOrNull
        assertEquals("GIT-R-OPEN-NF", error?.code)
    }

    @Test
    fun `a folder that doesn't exist is not found, without starting git there`(sandbox: GitSandbox) = runBlocking {
        val error = sandbox.access().locate(sandbox.root.resolve("gone")).errorOrNull
        assertEquals("GIT-R-OPEN-NF", error?.code)
        assertTrue(sandbox.violations.isEmpty())
    }

    @Test
    fun `a bare repository has no working tree`(sandbox: GitSandbox) = runBlocking {
        val bare = sandbox.root.resolve("srv.git")
        sandbox.git(sandbox.root, "init", "--quiet", "--bare", bare.toString())
        val location = sandbox.access().locate(bare).getOrThrow()
        assertNull(location.workTree)
        assertTrue(location.isBare)
        assertEquals(bare.real(), location.gitDir)
    }

    @Test
    fun `a linked worktree has its own git dir and the shared common dir`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.simple()
        val worktree = sandbox.root.resolve("app-feature")
        sandbox.git(repo, "worktree", "add", "--quiet", "-b", "feature", worktree.toString())
        val access = sandbox.access()
        val location = access.locate(worktree).getOrThrow()
        assertEquals(worktree.real(), location.workTree)
        assertEquals(repo.resolve(".git").real(), location.commonDir)
        assertEquals(repo.resolve(".git/worktrees").real(), location.gitDir.parent)
        // From inside the worktree's git dir, the gitdir file leads back to the worktree.
        assertEquals(worktree.real(), access.locate(location.gitDir).getOrThrow().workTree)
    }

    @Test
    fun `a repository owned by someone else is refused, and trusting it lets it open`(sandbox: GitSandbox) =
        runBlocking {
            val repo = sandbox.simple()
            // Git's own test switch makes every repository look owned by another user.
            val runner =
                ProcessGitRunner(
                    GitRunnerConfig(
                        environment = sandbox.environment + ("GIT_TEST_ASSUME_DIFFERENT_OWNER" to "1"),
                        launcher = sandbox.launcher,
                    ),
                )
            val access = GitDataModule(runner).access
            val error = assertIs<GitUnsafeOwnerException>(access.locate(repo).errorOrNull)
            assertEquals("GIT-R-OPEN-OWN", error.code)
            assertTrue(error.repositoryPath.isNotEmpty(), error.stderr)
            assertEquals(error.repositoryPath, access.untrustedFolder(error))
            assertEquals(null, access.untrustedFolder(IllegalStateException("not git")))

            access.trust(error.repositoryPath).getOrThrow()
            assertEquals(repo.real(), access.locate(repo).getOrThrow().workTree)
            val global = Files.readString(sandbox.globalConfig)
            // `git config` writes it as a [safe] section, in the sandbox's global file only.
            assertTrue("[safe]" in global && "directory = ${error.repositoryPath}" in global, global)
        }

    @Test
    fun `the summary reads a new repository's unborn branch`(sandbox: GitSandbox) = runBlocking {
        val access = sandbox.access()
        val location = access.init(sandbox.root.resolve("new/app"), defaultBranch = "trunk").getOrThrow()
        val summary = access.summary(location.workTree!!).getOrThrow()
        assertEquals(Head.Branch("trunk", null), summary.head)
        assertEquals(false, summary.dirty)
    }

    @Test
    fun `cloning reports progress, then the new repository`(sandbox: GitSandbox) = runBlocking {
        val source = sandbox.simple("source")
        val target = sandbox.root.resolve("clones/copy")
        val steps =
            sandbox.access().clone(CloneRequest(url = source.toUri().toString(), directory = target)).toList()
        assertTrue(steps.dropLast(1).all { it is CloneProgress.Phase }, steps.toString())
        assertTrue(steps.any { it is CloneProgress.Phase && it.name == "Receiving objects" }, steps.toString())
        val finished = assertIs<CloneProgress.Finished>(steps.last())
        assertEquals(target.real(), finished.location.workTree)
        assertEquals("2", sandbox.git(target, "rev-list", "--count", "HEAD").trim())
    }

    @Test
    fun `a shallow single-branch clone`(sandbox: GitSandbox) = runBlocking {
        val source = sandbox.simple("source")
        val target = sandbox.root.resolve("shallow")
        sandbox.access()
            .clone(CloneRequest(source.toUri().toString(), target, depth = 1, singleBranch = true))
            .toList()
        assertEquals("true", sandbox.git(target, "rev-parse", "--is-shallow-repository").trim())
        assertEquals("1", sandbox.git(target, "rev-list", "--count", "HEAD").trim())
    }

    @Test
    fun `cloning into a folder that isn't empty fails with git's reason`(sandbox: GitSandbox) {
        val source = sandbox.simple("source")
        val target = sandbox.dir("taken")
        Files.writeString(target.resolve("file.txt"), "x")
        val error =
            assertFailsWith<GitException> {
                runBlocking { sandbox.access().clone(CloneRequest(source.toUri().toString(), target)).toList() }
            }
        assertEquals("CLON", error.function)
        assertTrue("already exists" in error.stderr, error.stderr)
    }

    @Test
    fun `init's first commit stages exactly the files named, literally`(sandbox: GitSandbox) = runBlocking<Unit> {
        val access = sandbox.access()
        val location = access.init(sandbox.root.resolve("fresh"), "main").getOrThrow()
        val tree = location.workTree!!
        Files.writeString(tree.resolve("README.md"), "# fresh\n")
        Files.writeString(tree.resolve(".gitignore"), "build/\n")
        // A file Init didn't write, and one whose name looks like a pattern: neither is staged.
        Files.writeString(tree.resolve("notes.txt"), "mine\n")
        Files.writeString(tree.resolve("*.md"), "not a pattern\n")
        access.initialCommit(tree, listOf("README.md", ".gitignore"), "Initial commit").getOrThrow()
        assertEquals(
            listOf(".gitignore", "README.md"),
            sandbox.git(tree, "ls-tree", "--name-only", "HEAD").trim().lines(),
        )
        assertEquals("Initial commit", sandbox.git(tree, "log", "-1", "--format=%s").trim())
    }

    @Test
    fun `a first commit without an identity fails with git's own words`(sandbox: GitSandbox) = runBlocking<Unit> {
        val access = sandbox.access()
        val tree = access.init(sandbox.root.resolve("anon"), "main").getOrThrow().workTree!!
        Files.writeString(tree.resolve("README.md"), "x\n")
        // The sandbox sets an author in the environment; this runner has none, and git must not guess one.
        val noIdentity =
            sandbox.environment + mapOf(
                "GIT_AUTHOR_NAME" to null,
                "GIT_AUTHOR_EMAIL" to null,
                "GIT_COMMITTER_NAME" to null,
                "GIT_COMMITTER_EMAIL" to null,
                "GIT_CONFIG_COUNT" to "1",
                "GIT_CONFIG_KEY_0" to "user.useConfigOnly",
                "GIT_CONFIG_VALUE_0" to "true",
            )
        val runner =
            ProcessGitRunner(
                GitRunnerConfig(
                    executable = sandbox.gitExecutable,
                    environment = noIdentity,
                    launcher = sandbox.launcher,
                ),
            )
        val bare = GitDataModule(runner).access
        val failure = bare.initialCommit(tree, listOf("README.md"), "Initial commit").errorOrNull
        assertEquals("GIT-R-CMIT-EXIT", (failure as? GitException)?.code)
    }

    @Test
    fun `a failed clone removes the folder it created, and leaves one that was there`(sandbox: GitSandbox) {
        val missing = sandbox.root.resolve("no-such-source")
        val created = sandbox.root.resolve("made-by-clone")
        assertFailsWith<GitException> {
            runBlocking { sandbox.access().clone(CloneRequest(missing.toUri().toString(), created)).toList() }
        }
        assertTrue(Files.notExists(created), "the clone's own folder is gone")
        val empty = sandbox.dir("mine")
        assertFailsWith<GitException> {
            runBlocking { sandbox.access().clone(CloneRequest(missing.toUri().toString(), empty)).toList() }
        }
        assertTrue(Files.isDirectory(empty), "an empty folder the user made stays")
    }

    @Test
    fun `cancelling a clone kills git and the processes it started, and removes the half-made folder`(
        sandbox: GitSandbox,
    ) = runBlocking<Unit> {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "the fake git is a shell script")
        val pids = sandbox.root.resolve("pids.txt")
        // A "git" that makes the folder, starts a child that outlives it, records both pids, and waits.
        val script = sandbox.root.resolve("slow-git.sh")
        Files.writeString(
            script,
            """
            #!/bin/sh
            for last; do :; done
            mkdir -p "${'$'}last"
            sleep 600 &
            echo "${'$'}${'$'} ${'$'}!" > "$pids"
            wait
            """.trimIndent() + "\n",
        )
        script.toFile().setExecutable(true)
        val target = sandbox.root.resolve("slow-clone")
        val access = GitDataModule(sandbox.runner(executable = script.toString())).access
        val job = launch(Dispatchers.Default) { access.clone(CloneRequest("file:///nowhere", target)).collect {} }
        withTimeout(10_000) { while (!Files.exists(pids) || Files.size(pids) == 0L) delay(20) }
        val (parent, child) = Files.readString(pids).trim().split(' ').map { it.toLong() }
        assertTrue(ProcessHandle.of(child).isPresent && ProcessHandle.of(parent).isPresent)
        job.cancelAndJoin()
        withTimeout(10_000) {
            while (ProcessHandle.of(child).map { it.isAlive }.orElse(false) ||
                ProcessHandle.of(parent).map { it.isAlive }.orElse(false)
            ) {
                delay(50)
            }
        }
        assertTrue(Files.notExists(target), "the half-made folder is removed")
    }
}
