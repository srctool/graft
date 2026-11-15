// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.graft.data.git.di.GitDataModule
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reflog, stashes and remotes on real repositories, on the latest Git and on 2.38 (`git-min`). */
@ExtendWith(GitSandboxExtension::class)
class ReflogRepositoryTest {
    @Test
    fun `pages chain each entry's old id to the next older entry`(sandbox: GitSandbox) = runBlocking<Unit> {
        val repo = ReflogRepos.history(sandbox)
        val reflogs = GitDataModule(sandbox.runner()).reflogs
        val first = reflogs.entries(repo, limit = 3).getOrThrow()
        val second = reflogs.entries(repo, skip = 3, limit = 3).getOrThrow()
        val last = reflogs.entries(repo, skip = 6, limit = 3).getOrThrow()
        assertTrue(first.hasMore)
        assertTrue(second.hasMore)
        assertFalse(last.hasMore)
        val all = first.entries + second.entries + last.entries
        assertEquals((0..7).toList(), all.map { it.index })
        assertEquals(all.drop(1).map { it.newId }, all.dropLast(1).map { it.oldId })
        assertNull(all.last().oldId)
        assertEquals(sandbox.git(repo, "rev-parse", "HEAD@{3}").trim(), all[3].newId.hex)
        assertTrue(all.all { it.ref == "HEAD" })
    }

    @Test
    fun `a branch's own reflog`(sandbox: GitSandbox) = runBlocking<Unit> {
        val repo = ReflogRepos.history(sandbox)
        val page = GitDataModule(sandbox.runner()).reflogs.entries(repo, ref = "refs/heads/main").getOrThrow()
        assertEquals(
            listOf(
                "reset: moving to HEAD~1",
                "commit (amend): three, amended",
                "commit: three",
                "commit: two",
                "commit (initial): one",
            ),
            page.entries.map { it.message },
        )
    }

    @Test
    fun `an expired reflog is empty, an unknown ref fails`(sandbox: GitSandbox) = runBlocking<Unit> {
        val repo = ReflogRepos.history(sandbox)
        val reflogs = GitDataModule(sandbox.runner()).reflogs
        sandbox.git(repo, "reflog", "expire", "--expire=now", "--expire-unreachable=now", "--all")
        val page = reflogs.entries(repo).getOrThrow()
        assertEquals(emptyList(), page.entries)
        assertFalse(page.hasMore)
        assertIs<Result.Failure>(reflogs.entries(repo, ref = "refs/heads/nope"))
    }

    @Test
    fun `reflogs are on by default, off when configured so or in a bare repository`(sandbox: GitSandbox) =
        runBlocking<Unit> {
            val reflogs = GitDataModule(sandbox.runner()).reflogs
            val repo = sandbox.repo("plain") { commit("one") { file("a.txt", "1") } }
            sandbox.git(repo, "config", "--unset", "core.logAllRefUpdates")
            assertTrue(reflogs.enabled(repo).getOrThrow())
            sandbox.git(repo, "config", "core.logAllRefUpdates", "false")
            assertFalse(reflogs.enabled(repo).getOrThrow())
            sandbox.git(repo, "config", "core.logAllRefUpdates", "always")
            assertTrue(reflogs.enabled(repo).getOrThrow())

            val bare = sandbox.dir("bare.git")
            sandbox.git(bare, "init", "--quiet", "--bare")
            assertFalse(reflogs.enabled(bare).getOrThrow())
        }

    @Test
    fun `stashes and remotes from a repository`(sandbox: GitSandbox) = runBlocking<Unit> {
        val data = GitDataModule(sandbox.runner())
        val stashRepo = ReflogRepos.stashes(sandbox)
        val stashes = data.stashes.list(stashRepo).getOrThrow()
        assertEquals(
            (0..2).map { sandbox.git(stashRepo, "rev-parse", "stash@{$it}").trim() },
            stashes.map { it.id.hex },
        )
        val remotes = data.remotes.list(ReflogRepos.remotes(sandbox)).getOrThrow()
        assertEquals("https://****@example.invalid/graft.git", remotes.single { it.name == "origin" }.fetchUrl)
        assertTrue(remotes.none { "s3cret" in it.toString() })
        assertEquals(
            emptyList(),
            data.stashes.list(
                sandbox.repo("clean") {
                    commit("one") { file("a.txt", "1") }
                },
            ).getOrThrow(),
        )
    }
}
