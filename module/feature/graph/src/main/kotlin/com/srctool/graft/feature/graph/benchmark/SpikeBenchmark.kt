// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.benchmark

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import com.srctool.graft.feature.graph.domain.api.usecases.LoadGraphUseCase
import com.srctool.graft.feature.graph.presentation.spike.GraphSpikeState
import com.srctool.graft.feature.graph.presentation.spike.GraphSpikeState.Phase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Temporary: the G0 spike's measurements of the graph screen, kept out of its view model. It watches the screen's
 * state from outside: when the first screen was drawn, when loading ended, the heap after it, and (with [scrollRows]
 * > 0) a scroll run on the page's list; then reports [SpikeResults] to [onResults]. Deleted with the spike in G1.
 *
 * [startNanos] is `System.nanoTime()` at `main()`, for the time to first screen including the window's startup.
 */
public class SpikeBenchmark(
    private val startNanos: Long,
    private val scrollRows: Int = 0,
    private val onResults: (SpikeResults) -> Unit,
) {
    /**
     * Measures one load of the screen whose states arrive on [states], from [openNanos] (when the screen opened).
     * [awaitFrame] returns the time of the next drawn frame; [scroll] scrolls that many rows and measures the frames.
     * Reports nothing when the load fails.
     */
    internal suspend fun run(
        states: Flow<GraphSpikeState>,
        openNanos: Long,
        awaitFrame: suspend () -> Long,
        scroll: suspend (rows: Int) -> ScrollResults,
        clock: () -> Long = System::nanoTime,
        measureHeapMb: () -> Long = ::usedHeapMb,
        background: CoroutineDispatcher = Dispatchers.Default,
    ) {
        val firstScreenReady =
            states.first { it.rowCount >= LoadGraphUseCase.FIRST_BATCH_ROWS || it.phase.isDone }
        if (firstScreenReady.phase == Phase.Failed) return
        val firstFrame = awaitFrame() // the frame that shows them
        val done = states.first { it.phase.isDone }
        if (done.phase == Phase.Failed) return
        val fullLoad = clock()
        val heap = withContext(background) { measureHeapMb() }
        val scrolled = if (scrollRows > 0) scroll(minOf(scrollRows, done.rowCount)) else null
        onResults(
            SpikeResults(
                rows = done.rowCount,
                widestLanes = done.widest,
                firstScreenMillis = (firstFrame - startNanos) / 1_000_000,
                openToFirstScreenMillis = (firstFrame - openNanos) / 1_000_000,
                fullLoadMillis = (fullLoad - startNanos) / 1_000_000,
                heapAfterLoadMb = heap,
                scroll = scrolled,
            ),
        )
    }

    private val Phase.isDone: Boolean get() = this == Phase.Loaded || this == Phase.Failed
}

/** Runs [benchmark] against the screen's [states] and the page's [listState], once. */
@Composable
internal fun SpikeBenchmarkEffect(
    benchmark: SpikeBenchmark,
    states: Flow<GraphSpikeState>,
    listState: LazyListState,
    rowHeightPx: Float,
) {
    LaunchedEffect(benchmark, states) {
        benchmark.run(
            states = states,
            openNanos = System.nanoTime(),
            awaitFrame = {
                withFrameNanos { }
                System.nanoTime()
            },
            scroll = { rows -> scrollBenchmark(listState, rowHeightPx, rows) },
        )
    }
}

/**
 * Scrolls [rowsToScroll] rows at a steady 40 rows a frame (about the speed of a hard fling on a 24 px row) and records
 * every frame's duration.
 */
private suspend fun scrollBenchmark(state: LazyListState, rowHeightPx: Float, rowsToScroll: Int): ScrollResults {
    state.scrollToItem(0)
    val perFrame = rowHeightPx * 40
    val frames = ArrayList<Long>()
    var previous = withFrameNanos { it }
    var scrolled = 0f
    val target = rowHeightPx * rowsToScroll
    while (scrolled < target) {
        scrolled += state.scrollBy(perFrame)
        val now = withFrameNanos { it }
        frames += now - previous
        previous = now
        if (!state.canScrollForward) break
    }
    val millis = frames.map { it / 1_000_000.0 }.sorted()
    val total = millis.sum()
    return ScrollResults(
        rowsScrolled = (scrolled / rowHeightPx).toInt(),
        frames = millis.size,
        averageFps = if (total > 0) millis.size * 1000 / total else 0.0,
        p99FrameMillis = millis.getOrElse((millis.size * 0.99).toInt().coerceAtMost(millis.size - 1)) { 0.0 },
        maxFrameMillis = millis.lastOrNull() ?: 0.0,
        framesOver32Millis = millis.count { it > 32 },
        framesOver16Millis = millis.count { it > 16.7 },
    )
}
