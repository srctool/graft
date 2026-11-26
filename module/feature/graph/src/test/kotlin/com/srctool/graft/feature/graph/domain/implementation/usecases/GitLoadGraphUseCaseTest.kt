// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.domain.implementation.usecases

import com.srctool.graft.data.git.api.models.CommitDetails
import com.srctool.graft.data.git.api.models.CommitLog
import com.srctool.graft.data.git.api.models.CommitRow
import com.srctool.graft.data.git.api.models.LogQuery
import com.srctool.graft.data.git.api.models.LogScope
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.People
import com.srctool.graft.data.git.api.repositories.CommitRepository
import com.srctool.graft.feature.graph.domain.api.models.GraphFilter
import com.srctool.graft.feature.graph.domain.api.models.GraphLoad
import com.srctool.graft.feature.graph.domain.api.usecases.LoadGraphUseCase.Companion.FIRST_BATCH_ROWS
import com.srctool.sinew.models.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitLoadGraphUseCaseTest {
    private val history = LaneLayoutBenchmarkTest.synthetic(5_000)
    private val repo = Paths.get("/repo")

    private fun useCase(rows: Flow<CommitRow>, dispatcher: TestDispatcher) =
        GitLoadGraphUseCase(FakeCommits(rows), ::LaneLayout, dispatcher)

    @Test
    fun `lays out every commit, a screen first, then reports the end`() = runTest {
        val steps = useCase(history.asFlow(), StandardTestDispatcher(testScheduler)).load(repo).toList()
        val first = assertIs<GraphLoad.Progress>(steps.first())
        assertEquals(FIRST_BATCH_ROWS, first.rowCount, "the first batch is one screen")
        val loaded = assertIs<GraphLoad.Loaded>(steps.last())
        assertEquals(5_000, loaded.rowCount)
        assertEquals(5_000, loaded.graph.size)
        assertTrue(steps.dropLast(1).all { it is GraphLoad.Progress })
        assertTrue(steps.all { it.graph === loaded.graph }, "one graph grows across the steps")
        // Progress only grows.
        val counts = steps.filterIsInstance<GraphLoad.Progress>().map { it.rowCount }
        assertEquals(counts.sorted(), counts)
    }

    @Test
    fun `the graph matches a plain layout of the same commits`() = runTest {
        val loaded = useCase(history.asFlow(), StandardTestDispatcher(testScheduler)).load(repo).toList().last()
        val plain = LaneLayout()
        history.forEachIndexed { i, commit ->
            val expected = plain.next(commit)
            val row = loaded.graph[i]
            assertEquals(expected.lane, row.lane)
            assertEquals(expected.color, row.color)
            assertEquals(expected.edges(), row.edges())
        }
        assertEquals(plain.widest, loaded.graph.widest)
    }

    @Test
    fun `first parent only draws no line toward the parents the log leaves out`() = runTest {
        // What `git log --first-parent` prints: the main line, each merge still naming the branch it merged.
        val mainLine = LaneLayoutTest.history("M2: M1 B2", "M1: M0 B1", "M0:", "B2:", "B1:").take(3)
        val graph =
            useCase(mainLine.asFlow(), StandardTestDispatcher(testScheduler))
                .load(repo, GraphFilter(firstParent = true))
                .toList()
                .last()
                .graph
        assertEquals(1, graph.widest)
        for (row in 0 until graph.size) assertEquals(-1, graph.through(row, 1), "row $row")
        assertEquals(2, graph[0].commit.parents.size, "the commit is still a merge")
    }

    @Test
    fun `the limit caps the commits`() = runTest {
        val loaded = useCase(history.asFlow(), StandardTestDispatcher(testScheduler)).load(repo, limit = 100).toList()
        assertEquals(100, assertIs<GraphLoad.Loaded>(loaded.last()).rowCount)
    }

    @Test
    fun `a failing log ends in Failed, keeping the rows before it`() = runTest {
        val boom = IllegalStateException("GIT-R-LOG-NF")
        val failing = flow {
            history.take(70).forEach { emit(it) }
            throw boom
        }
        val steps = useCase(failing, StandardTestDispatcher(testScheduler)).load(repo).toList()
        val failed = assertIs<GraphLoad.Failed>(steps.last())
        // Coroutines may hand on a copy with a recovered stack trace, so compare type and message.
        assertIs<IllegalStateException>(failed.error)
        assertEquals(boom.message, failed.error.message)
        assertEquals(70, failed.graph.size)
        assertTrue(steps.none { it is GraphLoad.Loaded })
    }

    @Test
    fun `each load starts a new graph`() = runTest {
        val useCase = useCase(history.take(10).asFlow(), StandardTestDispatcher(testScheduler))
        val first = useCase.load(repo).toList().last().graph
        val second = useCase.load(repo).toList().last().graph
        assertTrue(first !== second)
        assertEquals(10, second.size)
    }
}

/** A repository whose log is [rows], for any path. */
internal class FakeCommits(private val rows: Flow<CommitRow>) : CommitRepository {
    override fun log(repository: Path, scope: LogScope, firstParent: Boolean): CommitLog = CommitLog(rows, People())

    override suspend fun details(repository: Path, id: ObjectId): Result<CommitDetails> =
        error("not used by these tests")

    override fun search(repository: Path, query: LogQuery, scope: LogScope): CommitLog =
        error("not used by these tests")

    override suspend fun hasCommitGraph(repository: Path): Result<Boolean> = error("not used by these tests")

    override suspend fun writeCommitGraph(repository: Path): Result<Unit> = error("not used by these tests")

    override suspend fun resolve(repository: Path, revision: String): Result<ObjectId?> =
        error("not used by these tests")
}
