// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.benchmark

import com.srctool.graft.feature.graph.presentation.spike.GraphSpikeState
import com.srctool.graft.feature.graph.presentation.spike.GraphSpikeState.Phase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The benchmark's sequencing, with fake frames, scrolling, clock and heap: it only watches the screen's state. */
class SpikeBenchmarkTest {
    private val ms = 1_000_000L
    private val states = MutableStateFlow(GraphSpikeState())
    private val results = ArrayList<SpikeResults>()
    private val scrolls = ArrayList<Int>()
    private var now = 0L

    // A plain child, not backgroundScope: advanceUntilIdle() doesn't run background work. Every run ends by itself.
    private fun TestScope.start(scrollRows: Int = 0) = launch {
        SpikeBenchmark(startNanos = 0, scrollRows = scrollRows) { results += it }.run(
            states = states,
            openNanos = 100 * ms,
            awaitFrame = { now },
            scroll = { rows ->
                scrolls += rows
                ScrollResults(rows, 10, 120.0, 9.0, 12.0, 0, 0)
            },
            clock = { now },
            measureHeapMb = { 230 },
            background = StandardTestDispatcher(testScheduler),
        )
    }

    private fun emit(rows: Int, phase: Phase, at: Long) {
        now = at * ms
        states.value = GraphSpikeState(rowCount = rows, widest = 7, phase = phase)
    }

    @Test
    fun `measures the first screen, the full load and the heap`() = runTest {
        start()
        advanceUntilIdle()
        emit(30, Phase.Loading, at = 900)
        advanceUntilIdle()
        assertTrue(results.isEmpty(), "half a screen isn't the first screen")
        emit(60, Phase.Loading, at = 1_300)
        advanceUntilIdle()
        emit(500_000, Phase.Loaded, at = 9_000)
        advanceUntilIdle()

        val r = results.single()
        assertEquals(500_000, r.rows)
        assertEquals(7, r.widestLanes)
        assertEquals(1_300, r.firstScreenMillis)
        assertEquals(1_200, r.openToFirstScreenMillis)
        assertEquals(9_000, r.fullLoadMillis)
        assertEquals(230, r.heapAfterLoadMb)
        assertNull(r.scroll)
        assertTrue(scrolls.isEmpty())
    }

    @Test
    fun `a history shorter than a screen counts as shown when it's loaded`() = runTest {
        start()
        emit(24, Phase.Loaded, at = 500)
        advanceUntilIdle()
        assertEquals(500, results.single().firstScreenMillis)
    }

    @Test
    fun `scrolls after the load, never past the rows`() = runTest {
        start(scrollRows = 100_000)
        emit(500, Phase.Loaded, at = 700)
        advanceUntilIdle()
        assertEquals(listOf(500), scrolls)
        assertEquals(500, results.single().scroll?.rowsScrolled)
    }

    @Test
    fun `a failed load reports nothing`() = runTest {
        start(scrollRows = 100)
        emit(60, Phase.Loading, at = 400)
        advanceUntilIdle()
        emit(70, Phase.Failed, at = 600)
        advanceUntilIdle()
        assertTrue(results.isEmpty())
        assertTrue(scrolls.isEmpty())
    }
}
