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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.srctool.graft.data.git.api.models.CommitLog
import com.srctool.graft.data.git.api.models.CommitRow
import com.srctool.graft.feature.graph.domain.GraphRow
import com.srctool.graft.feature.graph.domain.LaneLayout
import com.srctool.graft.feature.graph.presentation.components.GraphColors
import com.srctool.graft.feature.graph.presentation.components.GraphLanes
import com.srctool.graft.feature.graph.presentation.components.LaneWidth
import com.srctool.graft.feature.graph.presentation.components.MAX_DRAWN_LANES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withContext

/*
 * G0 Task 8: the throwaway graph spike that checks ADR-1 (Compose Desktop can draw a 500k-commit graph at 60 fps in
 * 400 MB). Plain Compose Foundation, no Camouflage or view model yet; deleted or rewritten in G1.
 */

/** What the spike measured; printed to stdout and shown in the header. */
public data class SpikeResults(
    val rows: Int,
    val widestLanes: Int,
    /** From `main()`: includes starting the JVM's Compose window. */
    val firstScreenMillis: Long,
    /** From the moment the graph starts reading the repository (the window is already up). */
    val openToFirstScreenMillis: Long,
    val fullLoadMillis: Long,
    val heapAfterLoadMb: Long,
    val scroll: ScrollResults?,
)

public data class ScrollResults(
    val rowsScrolled: Int,
    val frames: Int,
    val averageFps: Double,
    val p99FrameMillis: Double,
    val maxFrameMillis: Double,
    val framesOver32Millis: Int,
    val framesOver16Millis: Int,
)

private val RowHeight = 24.dp
private const val FIRST_SCREEN_ROWS = 60

/**
 * The spike: streams [log] through the lane layout into a virtualized list. With [autoScrollRows] > 0 it waits for
 * the full load, measures memory, then scrolls that many rows at a steady fling speed and measures frame times;
 * [onResults] gets the numbers (also after a load-only run).
 */
@Composable
public fun GraphSpike(
    log: CommitLog,
    startNanos: Long,
    autoScrollRows: Int = 0,
    limit: Int = Int.MAX_VALUE,
    onResults: (SpikeResults) -> Unit = {},
) {
    val rows = remember { ArrayList<GraphRow>(1 shl 20) }
    // Laid out on the UI thread as batches arrive, so drawing never reads it while it changes.
    val lanes = remember { LaneLayout() }
    var count by remember { mutableIntStateOf(0) }
    var widest by remember { mutableIntStateOf(1) }
    var status by remember { mutableStateOf("loading…") }
    val listState = rememberLazyListState()
    val rowHeightPx = with(LocalDensity.current) { RowHeight.toPx() }

    LaunchedEffect(log) {
        val openNanos = System.nanoTime()
        var openToFirst = -1L
        var firstScreen = -1L
        log.rows.take(limit).batched().flowOn(Dispatchers.Default).collect { batch ->
            batch.forEach { rows += lanes.next(it) }
            widest = lanes.widest
            count = rows.size
            if (firstScreen < 0 && count >= FIRST_SCREEN_ROWS) {
                val batchAt = (System.nanoTime() - openNanos) / 1_000_000
                withFrameNanos { } // the frame that shows them
                println(
                    "GRAFT_SPIKE_TIMING first batch at $batchAt ms after open; its frame at ${(System.nanoTime() - openNanos) / 1_000_000} ms",
                )
                firstScreen = (System.nanoTime() - startNanos) / 1_000_000
                openToFirst = (System.nanoTime() - openNanos) / 1_000_000
                status = "first screen in $firstScreen ms; loading…"
            }
        }
        val fullLoad = (System.nanoTime() - startNanos) / 1_000_000
        if (firstScreen < 0) firstScreen = fullLoad
        if (openToFirst < 0) openToFirst = (System.nanoTime() - openNanos) / 1_000_000
        val heap = withContext(Dispatchers.Default) { usedHeapMb() }
        status = "$count commits; first screen $firstScreen ms; full load $fullLoad ms; heap $heap MB"
        val scroll = if (autoScrollRows >
            0
        ) {
            scrollBenchmark(listState, rowHeightPx, minOf(autoScrollRows, count))
        } else {
            null
        }
        if (scroll !=
            null
        ) {
            status +=
                "; scroll ${"%.1f".format(scroll.averageFps)} fps, max ${"%.1f".format(scroll.maxFrameMillis)} ms"
        }
        onResults(SpikeResults(count, widest, firstScreen, openToFirst, fullLoad, heap, scroll))
    }

    val graphWidth = LaneWidth * minOf(widest, MAX_DRAWN_LANES)
    Column(Modifier.fillMaxSize().background(GraphColors.Background)) {
        BasicText(status, Modifier.padding(8.dp), style = TextStyle(color = GraphColors.Muted, fontSize = 12.sp))
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(count) { index ->
                    val row = rows[index]
                    Row(Modifier.fillMaxWidth().height(RowHeight), verticalAlignment = Alignment.CenterVertically) {
                        GraphLanes(row, lanes, Modifier.width(graphWidth).height(RowHeight))
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

/**
 * Hands rows to the UI in batches: small at first so the first screen shows at once, then at most every 50 ms so the
 * list isn't recomposed for every one of a million rows.
 */
private fun Flow<CommitRow>.batched(): Flow<List<CommitRow>> = flow {
    var batch = ArrayList<CommitRow>(FIRST_SCREEN_ROWS)
    var sent = 0
    var last = System.nanoTime()
    collect { row ->
        batch += row
        val now = System.nanoTime()
        if ((sent == 0 && batch.size >= FIRST_SCREEN_ROWS) || now - last > 50_000_000) {
            emit(batch)
            sent += batch.size
            batch = ArrayList(maxOf(1024, batch.size * 2))
            last = now
        }
    }
    if (batch.isNotEmpty()) emit(batch)
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

private fun usedHeapMb(): Long {
    val runtime = Runtime.getRuntime()
    repeat(3) {
        System.gc()
        Thread.sleep(100)
    }
    return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
}
