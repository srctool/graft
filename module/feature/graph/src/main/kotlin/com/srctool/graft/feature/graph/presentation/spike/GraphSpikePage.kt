// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.spike

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.srctool.graft.feature.graph.benchmark.SpikeBenchmark
import com.srctool.graft.feature.graph.benchmark.SpikeBenchmarkEffect
import com.srctool.graft.feature.graph.presentation.components.GraphColors
import com.srctool.graft.feature.graph.presentation.components.GraphLanes
import com.srctool.graft.feature.graph.presentation.components.LaneWidth
import com.srctool.graft.feature.graph.presentation.components.MAX_DRAWN_LANES
import com.srctool.graft.feature.graph.presentation.spike.GraphSpikeState.Phase

private val RowHeight = 24.dp

/**
 * The spike page: a virtualized list of fixed-height rows, one `Canvas` per row for the lanes, rendering
 * [viewModel]'s state. With a [benchmark], it also lets the benchmark watch the state and scroll the list.
 */
@Composable
internal fun GraphSpikePage(viewModel: GraphSpikeViewModel, benchmark: SpikeBenchmark?) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val rowHeightPx = with(LocalDensity.current) { RowHeight.toPx() }

    LaunchedEffect(viewModel) { viewModel.onEvent(GraphSpikeEvent.Opened) }
    if (benchmark != null) SpikeBenchmarkEffect(benchmark, viewModel.state, listState, rowHeightPx)

    val graphWidth = LaneWidth * minOf(state.widest, MAX_DRAWN_LANES)
    Column(Modifier.fillMaxSize().background(GraphColors.Background)) {
        BasicText(
            status(state),
            Modifier.padding(8.dp),
            style = TextStyle(color = GraphColors.Muted, fontSize = 12.sp),
        )
        Box(Modifier.fillMaxSize()) {
            val graph = state.graph
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                // Rows below rowCount are laid out; the graph is set by then.
                if (graph != null) {
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
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd))
        }
    }
}

private fun status(state: GraphSpikeState): String = when (state.phase) {
    Phase.Idle, Phase.Loading -> "${state.rowCount} commits, loading…"
    Phase.Loaded -> "${state.rowCount} commits"
    Phase.Failed -> "git log failed after ${state.rowCount} commits: ${state.error}"
}
