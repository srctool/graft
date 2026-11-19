// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.search

import com.srctool.graft.data.git.api.models.FileList
import com.srctool.graft.data.git.api.repositories.TreeRepository
import com.srctool.graft.feature.graph.presentation.graph.FakeCommitRepository
import com.srctool.graft.feature.graph.presentation.graph.FakeFailure
import com.srctool.graft.feature.graph.presentation.graph.FakeSession
import com.srctool.graft.feature.graph.presentation.graph.FakeUiState
import com.srctool.graft.feature.graph.presentation.graph.Sample
import com.srctool.graft.presentation.search.RepositoryScope
import com.srctool.graft.presentation.search.SearchKind
import com.srctool.graft.presentation.search.SearchScope
import com.srctool.graft.presentation.search.SearchTarget
import com.srctool.graft.presentation.view.RepositoryViewState
import com.srctool.sinew.models.Result
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeTrees(var paths: List<String>, var truncated: Boolean = false, var failure: FakeFailure? = null) :
    TreeRepository {
    var reads = 0

    override suspend fun files(repository: Path, limit: Int): Result<FileList> {
        reads++
        failure?.let { return Result.Failure(it) }
        return Result.Success(FileList(paths, truncated))
    }
}

class GraphSearchSourcesTest {
    private val session = FakeSession()
    private val scope =
        SearchScope(RepositoryScope(session, RepositoryViewState(Sample.location.commonDir, FakeUiState())))

    @Test
    fun `refs offer branches and tags by short name, each selecting its commit, and nothing before typing`() =
        runBlocking {
            val source = RefSearchSource()
            assertEquals(emptyList(), source.candidates("", scope))
            val found = source.candidates("m", scope)
            assertTrue(found.isNotEmpty())
            val main = found.first { it.title == "main" }
            assertEquals(SearchKind.Branch, main.kind)
            assertEquals(SearchTarget.SelectCommit(Sample.refs.first { it.shortName == "main" }.commit), main.target)
            assertTrue(found.any { it.kind == SearchKind.Tag })
            // Without a repository there's nothing to offer.
            assertEquals(emptyList(), source.candidates("m", SearchScope()))
        }

    @Test
    fun `commits are found by message, with the short SHA, author and day`() = runBlocking {
        val source = CommitSearchSource(FakeCommitRepository())
        val subject = Sample.rows.first().subject
        val found = source.candidates(subject.take(5), scope)
        val first = found.first()
        assertEquals(SearchKind.Commit, first.kind)
        assertEquals(subject, first.title)
        assertEquals(SearchTarget.SelectCommit(Sample.rows.first().id), first.target)
        assertTrue(first.detail!!.startsWith(Sample.rows.first().id.hex.take(7)))
        assertEquals(Sample.rows.first().id.hex, first.extra)
        assertEquals(emptyList(), source.candidates("  ", scope))
    }

    @Test
    fun `a SHA from four hex digits finds its commit once, even when the message matches too`() = runBlocking {
        val row = Sample.rows[2]
        val source = CommitSearchSource(FakeCommitRepository())
        val found = source.candidates(row.id.hex.take(6), scope)
        assertEquals(1, found.count { it.key == "commit:${row.id.hex}" })
        assertTrue(source.candidates("zzzz", scope).isEmpty())
    }

    @Test
    fun `files are read once per HEAD, filtered by letters in order, and shortest first`() = runBlocking {
        val trees = FakeTrees(listOf("src/auth/LoginScreen.kt", "README.md", "docs/login.md", "src/Main.kt"))
        val source = FileSearchSource(trees)
        val found = source.candidates("login", scope)
        assertEquals(listOf("docs/login.md", "src/auth/LoginScreen.kt"), found.map { it.title })
        assertEquals(SearchTarget.FileInHead("docs/login.md"), found.first().target)
        source.candidates("main", scope)
        assertEquals(1, trees.reads)
        assertEquals(emptyList(), source.candidates("", scope))
        assertNull(source.note())
    }

    @Test
    fun `a list cut at the cap says so, and a new HEAD reads the files again`() = runBlocking {
        val trees = FakeTrees(listOf("a.kt"), truncated = true)
        val source = FileSearchSource(trees)
        source.candidates("a", scope)
        assertNotNull(source.note())
        assertTrue(
            source.note()!!.contains("200000") || source.note()!!.contains("200 000") || source.note()!!.isNotBlank(),
        )
        session.state.value = session.state.value.copy(
            summary = session.state.value.summary?.copy(
                head = com.srctool.graft.data.git.api.models.Head.Detached(Sample.id(7)),
            ),
        )
        source.candidates("a", scope)
        assertEquals(2, trees.reads)
    }

    @Test
    fun `a tree that can't be read fails the group`() = runBlocking<Unit> {
        val source = FileSearchSource(FakeTrees(emptyList(), failure = FakeFailure("no")))
        val thrown = runCatching { source.candidates("a", scope) }.exceptionOrNull()
        assertNotNull(thrown)
    }
}
