// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.graft.data.git.api.models.AheadBehind
import com.srctool.graft.data.git.api.models.LogQuery
import com.srctool.graft.data.git.api.models.LogScope
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.di.GitDataModule
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Graph search and Compare on real repositories, on the latest Git and on 2.38 (`git-min`). */
@ExtendWith(GitSandboxExtension::class)
class SearchCompareTest {
    private class Built(val path: Path, val forkPoint: String)

    /**
     * main: init → "Fix login timeout" → "main edit" (conflicts with feature) → "Docs [draft]*" (a path with brackets,
     * valid on Windows too) → merge of
     * `side`. feature (from "Fix login timeout", by Ann Lee): "Add retry", "conflicting edit". side: "Side work".
     * orphan: unrelated history.
     */
    private fun GitSandbox.build(): Built {
        val repo =
            repo("search") {
                commit("init") { file("a.txt", "base\n") }
                commit("Fix login timeout") { file("login.kt", "val timeout = 30\n") }
            }
        fun git(vararg args: String): String = git(repo, *args)
        fun write(path: String, text: String) {
            val file = repo.resolve(path)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
        fun commit(message: String, vararg extra: String) {
            git("add", "--all")
            git("commit", "--quiet", *extra, "-m", message)
        }
        val forkPoint = git("rev-parse", "HEAD").trim()
        git("switch", "--quiet", "-c", "feature")
        write("retry.kt", "retry()\n")
        commit("Add retry", "--author=Ann Lee <ann@example.invalid>")
        write("a.txt", "feature\n")
        commit("conflicting edit", "--author=Ann Lee <ann@example.invalid>")
        git("switch", "--quiet", "main")
        git("switch", "--quiet", "-c", "side")
        write("side.txt", "side\n")
        commit("Side work")
        git("switch", "--quiet", "main")
        write("a.txt", "main\n")
        commit("main edit")
        write("docs/[odd] name.md", "doc\n")
        commit("Docs [draft]*")
        git("merge", "--quiet", "--no-ff", "-m", "Merge side", "side")
        git("switch", "--quiet", "--orphan", "orphan")
        write("o.txt", "o\n")
        commit("Orphan root")
        git("switch", "--quiet", "main")
        return Built(repo, forkPoint)
    }

    private suspend fun GitSandbox.subjects(path: Path, query: LogQuery): List<String> =
        GitDataModule(runner()).commits.search(path, query).rows.map { it.subject }.toList()

    @Test
    fun `message search is plain text and ignores case`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.build().path
        assertEquals(listOf("Fix login timeout"), sandbox.subjects(repo, LogQuery(message = "LOGIN")))
        // Brackets and a star are text, not a pattern.
        assertEquals(listOf("Docs [draft]*"), sandbox.subjects(repo, LogQuery(message = "[draft]*")))
        assertEquals(emptyList(), sandbox.subjects(repo, LogQuery(message = "LOGIN", ignoreCase = false)))
    }

    @Test
    fun `author and message must both match`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.build().path
        assertEquals(listOf("conflicting edit", "Add retry"), sandbox.subjects(repo, LogQuery(author = "ann lee")))
        assertEquals(listOf("Add retry"), sandbox.subjects(repo, LogQuery(author = "Ann", message = "retry")))
        assertEquals(emptyList(), sandbox.subjects(repo, LogQuery(author = "Ann", message = "login")))
    }

    @Test
    fun `a path is literal, brackets and all`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.build().path
        assertEquals(listOf("Docs [draft]*"), sandbox.subjects(repo, LogQuery(path = "docs/[odd] name.md")))
        assertEquals(emptyList(), sandbox.subjects(repo, LogQuery(path = "docs/*")))
        assertTrue("init" in sandbox.subjects(repo, LogQuery(path = "a.txt")))
    }

    @Test
    fun `content search with git's pickaxe, as text or a pattern`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.build().path
        assertEquals(listOf("Fix login timeout"), sandbox.subjects(repo, LogQuery(content = "timeout = 30")))
        assertEquals(
            listOf("Fix login timeout"),
            sandbox.subjects(repo, LogQuery(content = "time.*= [0-9]+", contentIsRegex = true)),
        )
    }

    @Test
    fun `first-parent leaves out what merges brought in`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.build().path
        // Search covers every ref like the graph, so `side` must go for its commit to be reachable only by the merge.
        sandbox.git(repo, "branch", "--quiet", "-D", "side")
        assertTrue("Side work" in sandbox.subjects(repo, LogQuery()))
        assertTrue("Side work" !in sandbox.subjects(repo, LogQuery(firstParent = true)))
    }

    @Test
    fun `commits in one branch and not the other, both ways`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.build().path
        val compare = GitDataModule(sandbox.runner()).compare
        val onlyFeature = compare.commits(repo, "main", "feature").rows.map { it.subject }.toList()
        assertEquals(listOf("conflicting edit", "Add retry"), onlyFeature)
        val onlyMain = compare.commits(repo, "feature", "main").rows.map { it.subject }.toList()
        assertEquals(setOf("Merge side", "Side work", "Docs [draft]*", "main edit"), onlyMain.toSet())
    }

    @Test
    fun `ahead, behind and the merge base`(sandbox: GitSandbox) = runBlocking<Unit> {
        val built = sandbox.build()
        val compare = GitDataModule(sandbox.runner()).compare
        assertEquals(
            AheadBehind(ahead = 2, behind = 4),
            compare.aheadBehind(built.path, "main", "feature").getOrThrow(),
        )
        assertEquals(ObjectId(built.forkPoint), compare.mergeBase(built.path, "main", "feature").getOrThrow())
        assertNull(compare.mergeBase(built.path, "main", "orphan").getOrThrow())
    }

    @Test
    fun `merge preview names the conflicts and changes nothing`(sandbox: GitSandbox) = runBlocking<Unit> {
        val repo = sandbox.build().path
        val compare = GitDataModule(sandbox.runner()).compare
        val head = sandbox.git(repo, "rev-parse", "HEAD")
        val status = sandbox.git(repo, "status", "--porcelain=v2", "--branch")

        val conflicted = compare.mergePreview(repo, "main", "feature").getOrThrow()
        assertEquals(listOf("a.txt"), conflicted.conflicts)
        assertTrue(!conflicted.clean)
        assertTrue(compare.mergePreview(repo, "feature", "side").getOrThrow().clean)

        assertEquals(head, sandbox.git(repo, "rev-parse", "HEAD"))
        assertEquals(status, sandbox.git(repo, "status", "--porcelain=v2", "--branch"))
    }

    private suspend fun GitSandbox.logged(path: Path, scope: LogScope): List<String> =
        GitDataModule(runner()).commits.log(path, scope).rows.map { it.subject }.toList()

    @Test
    fun `a hidden ref's own commits leave the graph`(sandbox: GitSandbox) = runBlocking<Unit> {
        val repo = sandbox.build().path
        val all = sandbox.logged(repo, LogScope.All())
        val hidden = sandbox.logged(repo, LogScope.All(hidden = setOf("refs/heads/feature", "refs/heads/orphan")))
        assertTrue("Add retry" in all && "Orphan root" in all)
        assertEquals(all - setOf("Add retry", "conflicting edit", "Orphan root"), hidden)
    }

    @Test
    fun `solo shows only the chosen refs' history`(sandbox: GitSandbox) = runBlocking<Unit> {
        val repo = sandbox.build().path
        assertEquals(
            listOf("conflicting edit", "Add retry", "Fix login timeout", "init"),
            sandbox.logged(repo, LogScope.Only(setOf("refs/heads/feature"))),
        )
        val search = GitDataModule(sandbox.runner()).commits
        val inMain = search.search(repo, LogQuery(message = "retry"), LogScope.Only(setOf("refs/heads/main")))
        assertEquals(emptyList(), inMain.rows.toList())
    }

    @Test
    fun `only full ref names reach git`() {
        assertFailsWith<IllegalArgumentException> { LogScope.Only(setOf("main")) }
        assertFailsWith<IllegalArgumentException> { LogScope.Only(setOf("--output=/tmp/x")) }
        assertFailsWith<IllegalArgumentException> { LogScope.All(hidden = setOf("-n1")) }
        assertFailsWith<IllegalArgumentException> { LogScope.Only(emptySet()) }
    }
}
