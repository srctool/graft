// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.spike

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.srctool.graft.feature.graph.presentation.components.GraphColors
import com.srctool.graft.feature.graph.presentation.components.GraphLanes
import com.srctool.graft.feature.graph.presentation.components.LaneWidth
import com.srctool.graft.feature.graph.presentation.components.MAX_DRAWN_LANES
import com.srctool.graft.feature.graph.presentation.spike.GraphSpikeState.Phase
import com.srctool.sinew.viewmodel.ObserveEffects

private val RowHeight = 24.dp

/**
 * The spike page: a virtualized list of fixed-height rows, one `Canvas` per row for the lanes. It renders
 * [viewModel]'s state and reports what only the page knows: the frame that showed the first screen, and the scroll
 * benchmark it runs on its own list.
 */
@Composable
internal fun GraphSpikePage(viewModel: GraphSpikeViewModel, onResults: (SpikeResults) -> Unit) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val rowHeightPx = with(LocalDensity.current) { RowHeight.toPx() }

    LaunchedEffect(viewModel) { viewModel.onEvent(GraphSpikeEvent.Opened) }

    val firstScreenReady = state.rowCount >= FIRST_SCREEN_ROWS || state.phase == Phase.Loaded
    LaunchedEffect(firstScreenReady) {
        if (firstScreenReady) {
            withFrameNanos { } // the frame that shows them
            viewModel.onEvent(GraphSpikeEvent.FirstScreenShown(System.nanoTime()))
        }
    }

    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is GraphSpikeEffect.RunScrollBenchmark ->
                viewModel.onEvent(GraphSpikeEvent.ScrollMeasured(scrollBenchmark(listState, rowHeightPx, effect.rows)))

            is GraphSpikeEffect.Finished -> onResults(effect.results)
        }
    }

    val graph = state.graph
    val graphWidth = LaneWidth * minOf(state.widest, MAX_DRAWN_LANES)
    Column(Modifier.fillMaxSize().background(GraphColors.Background)) {
        BasicText(
            status(state),
            Modifier.padding(8.dp),
            style = TextStyle(color = GraphColors.Muted, fontSize = 12.sp),
        )
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(state.rowCount) { index ->
                    val row = graph[index]
                    Row(Modifier.fillMaxWidth().height(RowHeight), verticalAlignment = Alignment.CenterVertically) {
                        GraphLanes(row, graph::through, Modifier.width(graphWidth).height(RowHeight))
                        BasicText(
                            row.commit.id.short(),
                            Modifier.width(72.dp),
                            style = TextStyle(
                                color = GraphColors.Muted,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                        BasicText(
                            row.commit.subject,
                            Modifier.weight(1f),
                            style = TextStyle(color = GraphColors.Foreground, fontSize = 13.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd))
        }
    }
}

private fun status(state: GraphSpikeState): String = buildString {
    when (state.phase) {
        Phase.Idle, Phase.Loading -> append("${state.rowCount} commits, loading…")
        Phase.Loaded -> append("${state.rowCount} commits")
        Phase.Failed -> append("git log failed: ${state.error}")
    }
    state.openToFirstScreenMillis?.let { append("; first screen $it ms after open") }
    state.fullLoadMillis?.let { append("; full load $it ms") }
    state.heapAfterLoadMb?.let { append("; heap $it MB") }
    state.scroll?.let {
        append("; scroll ${"%.1f".format(it.averageFps)} fps, max ${"%.1f".format(it.maxFrameMillis)} ms")
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
