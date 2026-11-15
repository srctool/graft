// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.spike

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.srctool.graft.data.git.api.models.CommitLog
import com.srctool.graft.data.git.api.models.CommitRow
import com.srctool.graft.data.git.api.models.People
import com.srctool.graft.data.git.api.repositories.CommitRepository
import com.srctool.graft.feature.graph.domain.implementation.usecases.LaneLayout
import com.srctool.graft.feature.graph.domain.implementation.usecases.LaneLayoutBenchmarkTest
import com.srctool.graft.feature.graph.presentation.spike.GraphSpikeState.Phase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GraphSpikeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private val history = LaneLayoutBenchmarkTest.synthetic(500)

    private fun viewModel(
        rows: Flow<CommitRow> = history.asFlow(),
        autoScrollRows: Int = 0,
        limit: Int = Int.MAX_VALUE,
    ) = ViewModelProvider.create(
        store,
        viewModelFactory {
            initializer {
                GraphSpikeViewModel(
                    GraphSpikeRoute("/repo", autoScrollRows, limit),
                    commits = FakeCommits(rows),
                    layout = LaneLayout(),
                    startNanos = 0,
                    background = dispatcher,
                    measureHeapMb = { 42 },
                )
            }
        },
    )[GraphSpikeViewModel::class]

    private fun TestScope.effectsOf(vm: GraphSpikeViewModel): List<GraphSpikeEffect> {
        val effects = ArrayList<GraphSpikeEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        return effects
    }

    @Test
    fun `opening loads every row and measures the heap`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(GraphSpikeEvent.Opened)
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(Phase.Loaded, state.phase)
        assertEquals(500, state.rowCount)
        assertEquals(500, state.graph.size)
        assertEquals(42, state.heapAfterLoadMb)
        assertTrue(state.widest >= 8)
    }

    @Test
    fun `opening twice loads once`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(GraphSpikeEvent.Opened)
        vm.onEvent(GraphSpikeEvent.Opened)
        advanceUntilIdle()
        assertEquals(500, vm.state.value.graph.size)
    }

    @Test
    fun `the results come once the first screen is shown`() = runTest(dispatcher) {
        val vm = viewModel()
        val effects = effectsOf(vm)
        vm.onEvent(GraphSpikeEvent.Opened)
        advanceUntilIdle()
        assertTrue(effects.isEmpty(), "no results before the page reports its first screen")

        vm.onEvent(GraphSpikeEvent.FirstScreenShown(nanos = 5_000_000))
        advanceUntilIdle()
        val results = assertIs<GraphSpikeEffect.Finished>(effects.single()).results
        assertEquals(500, results.rows)
        assertEquals(5, results.firstScreenMillis)
        assertNull(results.scroll)
    }

    @Test
    fun `with a scroll benchmark the page is asked to scroll, then the results include it`() = runTest(dispatcher) {
        val vm = viewModel(autoScrollRows = 10_000)
        val effects = effectsOf(vm)
        vm.onEvent(GraphSpikeEvent.Opened)
        vm.onEvent(GraphSpikeEvent.FirstScreenShown(nanos = 1_000_000))
        advanceUntilIdle()
        // Never more rows than were loaded.
        assertEquals(GraphSpikeEffect.RunScrollBenchmark(500), effects.single())

        val scroll = ScrollResults(500, 13, 120.0, 9.0, 12.0, 0, 0)
        vm.onEvent(GraphSpikeEvent.ScrollMeasured(scroll))
        advanceUntilIdle()
        assertEquals(scroll, assertIs<GraphSpikeEffect.Finished>(effects.last()).results.scroll)
        assertEquals(2, effects.size)
    }

    @Test
    fun `the limit caps the rows`() = runTest(dispatcher) {
        val vm = viewModel(limit = 120)
        vm.onEvent(GraphSpikeEvent.Opened)
        advanceUntilIdle()
        assertEquals(120, vm.state.value.rowCount)
    }

    @Test
    fun `a failing log is shown as failed, not thrown`() = runTest(dispatcher) {
        val failing = flow {
            history.take(70).forEach { emit(it) }
            throw IllegalStateException("git log failed")
        }
        val vm = viewModel(rows = failing)
        val effects = effectsOf(vm)
        vm.onEvent(GraphSpikeEvent.Opened)
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(Phase.Failed, state.phase)
        assertNotNull(state.error)
        assertTrue(effects.isEmpty())
    }
}

/** A repository whose log is [rows], for any path. */
private class FakeCommits(private val rows: Flow<CommitRow>) : CommitRepository {
    override fun log(repository: Path): CommitLog = CommitLog(rows, People())
}
