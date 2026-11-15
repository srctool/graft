// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.spike

import com.srctool.graft.data.git.api.models.CommitRow
import com.srctool.graft.data.git.api.repositories.CommitRepository
import com.srctool.graft.feature.graph.domain.api.usecases.GraphLayout
import com.srctool.graft.feature.graph.presentation.spike.GraphSpikeAction.Load
import com.srctool.graft.feature.graph.presentation.spike.GraphSpikeAction.RecordFirstScreen
import com.srctool.graft.feature.graph.presentation.spike.GraphSpikeAction.RecordScroll
import com.srctool.graft.feature.graph.presentation.spike.GraphSpikeState.Phase
import com.srctool.sinew.viewmodel.EventActionViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withContext
import java.nio.file.Paths

/** Rows in the first batch: about one screen, so it shows before the rest of the history arrives. */
internal const val FIRST_SCREEN_ROWS = 60

/**
 * Loads [route]'s repository history from [commits] through [layout], measures the load and the heap, asks the page for the scroll benchmark when
 * the route asks for one, and reports [GraphSpikeEffect.Finished] once every number is in.
 *
 * Parsing runs on [background]; laying out and appending run in [viewModelScope] on the main thread, the same thread
 * that draws the rows.
 */
internal class GraphSpikeViewModel(
    private val route: GraphSpikeRoute,
    private val commits: CommitRepository,
    layout: GraphLayout,
    private val startNanos: Long,
    private val background: CoroutineDispatcher = Dispatchers.Default,
    private val measureHeapMb: () -> Long = ::usedHeapMb,
) : EventActionViewModel<GraphSpikeState, GraphSpikeEvent, GraphSpikeAction, GraphSpikeEffect>(
    GraphSpikeState(GraphRows(layout)),
) {
    private var opened = false
    private var finished = false

    override fun mapEventToAction(event: GraphSpikeEvent): GraphSpikeAction? = when (event) {
        GraphSpikeEvent.Opened -> if (opened) null else Load.also { opened = true }

        is GraphSpikeEvent.FirstScreenShown ->
            if (currentState.firstScreenMillis == null) RecordFirstScreen(event.nanos) else null

        is GraphSpikeEvent.ScrollMeasured -> if (currentState.scroll == null) RecordScroll(event.results) else null
    }

    override suspend fun onAction(action: GraphSpikeAction) {
        when (action) {
            Load -> load()

            is RecordFirstScreen -> setState {
                copy(
                    firstScreenMillis = (action.nanos - startNanos) / 1_000_000,
                    openToFirstScreenMillis = (action.nanos - openNanos) / 1_000_000,
                )
            }

            is RecordScroll -> setState { copy(scroll = action.results) }
        }
        finishIfComplete()
    }

    private suspend fun load() {
        setState { copy(phase = Phase.Loading, openNanos = System.nanoTime()) }
        val graph = currentState.graph
        commits
            .log(Paths.get(route.repository))
            .rows
            .take(route.limit)
            .batched()
            .flowOn(background)
            .catch { e ->
                // A stream reports its failure to the collector (CommitRepository.log); show it instead of crashing.
                setState { copy(phase = Phase.Failed, error = e.toString()) }
            }.collect { batch ->
                batch.forEach(graph::add)
                setState { copy(rowCount = graph.size, widest = graph.widest) }
            }
        if (currentState.phase == Phase.Failed) return
        val fullLoad = (System.nanoTime() - startNanos) / 1_000_000
        val heap = withContext(background) { measureHeapMb() }
        setState { copy(phase = Phase.Loaded, fullLoadMillis = fullLoad, heapAfterLoadMb = heap) }
        if (route.autoScrollRows > 0) {
            emitEffect(GraphSpikeEffect.RunScrollBenchmark(minOf(route.autoScrollRows, currentState.rowCount)))
        }
    }

    /** Reports the results once: after the load, the first screen and (when asked for) the scroll benchmark. */
    private fun finishIfComplete() {
        val s = currentState
        if (finished || s.phase != Phase.Loaded) return
        val firstScreen = s.firstScreenMillis ?: return
        if (route.autoScrollRows > 0 && s.scroll == null) return
        finished = true
        emitEffect(
            GraphSpikeEffect.Finished(
                SpikeResults(
                    rows = s.rowCount,
                    widestLanes = s.widest,
                    firstScreenMillis = firstScreen,
                    openToFirstScreenMillis = s.openToFirstScreenMillis ?: 0,
                    fullLoadMillis = s.fullLoadMillis ?: 0,
                    heapAfterLoadMb = s.heapAfterLoadMb ?: 0,
                    scroll = s.scroll,
                ),
            ),
        )
    }
}

/**
 * Hands rows on in batches: the first one as soon as it fills a screen, then at most every 50 ms, so the list isn't
 * recomposed for every one of a million rows.
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

private fun usedHeapMb(): Long {
    val runtime = Runtime.getRuntime()
    repeat(3) {
        System.gc()
        Thread.sleep(100)
    }
    return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
}
