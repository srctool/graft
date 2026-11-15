// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.spike

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.srctool.graft.feature.graph.domain.api.models.GraphLoad
import com.srctool.graft.feature.graph.domain.api.models.GraphRow
import com.srctool.graft.feature.graph.domain.api.models.LaidOutGraph
import com.srctool.graft.feature.graph.domain.api.usecases.LoadGraphUseCase
import com.srctool.graft.feature.graph.presentation.spike.GraphSpikeState.Phase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The view model only folds [GraphLoad] steps into state; a fake use case feeds it. */
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

    private val graph = FakeGraph(500)
    private val loading = listOf(GraphLoad.Progress(graph, 60, 9), GraphLoad.Progress(graph, 400, 9))
    private val loaded = loading + GraphLoad.Loaded(graph, 500, 9)

    private fun viewModel(load: FakeLoad, limit: Int = Int.MAX_VALUE) = ViewModelProvider.create(
        store,
        viewModelFactory { initializer { GraphSpikeViewModel(GraphSpikeRoute("/repo", limit), load) } },
    )[GraphSpikeViewModel::class]

    @Test
    fun `before opening nothing loads`() = runTest(dispatcher) {
        val load = FakeLoad(loaded)
        val vm = viewModel(load)
        advanceUntilIdle()
        assertEquals(Phase.Idle, vm.state.value.phase)
        assertTrue(load.calls.isEmpty())
    }

    @Test
    fun `opening loads the route's repository and folds every step`() = runTest(dispatcher) {
        val load = FakeLoad(loaded)
        val vm = viewModel(load, limit = 1_000)
        vm.onEvent(GraphSpikeEvent.Opened)
        advanceUntilIdle()
        assertEquals(listOf(Paths.get("/repo") to 1_000), load.calls)
        val state = vm.state.value
        assertEquals(Phase.Loaded, state.phase)
        assertSame(graph, state.graph)
        assertEquals(500, state.rowCount)
        assertEquals(9, state.widest)
        assertNull(state.error)
    }

    @Test
    fun `while loading, the rows so far show`() = runTest(dispatcher) {
        val vm = viewModel(FakeLoad(loading))
        vm.onEvent(GraphSpikeEvent.Opened)
        advanceUntilIdle()
        assertEquals(Phase.Loading, vm.state.value.phase)
        assertEquals(400, vm.state.value.rowCount)
    }

    @Test
    fun `opening twice loads once`() = runTest(dispatcher) {
        val load = FakeLoad(loaded)
        val vm = viewModel(load)
        vm.onEvent(GraphSpikeEvent.Opened)
        vm.onEvent(GraphSpikeEvent.Opened)
        advanceUntilIdle()
        assertEquals(1, load.calls.size)
    }

    @Test
    fun `a failed load keeps its rows and shows the error`() = runTest(dispatcher) {
        val vm = viewModel(FakeLoad(loading.take(1) + GraphLoad.Failed(graph, IllegalStateException("GIT-R-LOG-NF"))))
        vm.onEvent(GraphSpikeEvent.Opened)
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(Phase.Failed, state.phase)
        assertEquals(60, state.rowCount)
        assertTrue(state.error!!.contains("GIT-R-LOG-NF"))
    }
}

/** Emits [steps] for any load, and records what was asked for. */
internal class FakeLoad(private val steps: List<GraphLoad>) : LoadGraphUseCase {
    val calls = ArrayList<Pair<Path, Int>>()

    override fun load(repository: Path, limit: Int): Flow<GraphLoad> {
        calls += repository to limit
        return flowOf(*steps.toTypedArray())
    }
}

/** A graph of [size] rows that only gets passed along; its rows are never read. */
internal class FakeGraph(override val size: Int) : LaidOutGraph {
    override val widest: Int = 9

    override fun get(index: Int): GraphRow = error("not read")

    override fun through(row: Int, lane: Int): Int = -1
}
