// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.spike

import com.srctool.graft.feature.graph.domain.api.models.GraphLoad
import com.srctool.graft.feature.graph.domain.api.usecases.LoadGraphUseCase
import com.srctool.graft.feature.graph.presentation.spike.GraphSpikeState.Phase
import com.srctool.sinew.viewmodel.EventActionViewModel
import java.nio.file.Paths

/**
 * Folds [loadGraph]'s steps for [route]'s repository into the page's state. It has no one-time effects, so its effect
 * type is `Nothing`. The spike's measurements watch this state from outside (`benchmark.SpikeBenchmark`).
 */
internal class GraphSpikeViewModel(private val route: GraphSpikeRoute, private val loadGraph: LoadGraphUseCase) :
    EventActionViewModel<GraphSpikeState, GraphSpikeEvent, GraphSpikeAction, Nothing>(GraphSpikeState()) {
    private var opened = false

    override fun mapEventToAction(event: GraphSpikeEvent): GraphSpikeAction? = when (event) {
        GraphSpikeEvent.Opened -> if (opened) null else GraphSpikeAction.Load.also { opened = true }
    }

    override suspend fun onAction(action: GraphSpikeAction) {
        when (action) {
            GraphSpikeAction.Load -> load()
        }
    }

    private suspend fun load() {
        setState { copy(phase = Phase.Loading) }
        loadGraph.load(Paths.get(route.repository), route.limit).collect { step ->
            setState {
                when (step) {
                    is GraphLoad.Progress -> copy(graph = step.graph, rowCount = step.rowCount, widest = step.widest)

                    is GraphLoad.Loaded ->
                        copy(graph = step.graph, rowCount = step.rowCount, widest = step.widest, phase = Phase.Loaded)

                    is GraphLoad.Failed -> copy(graph = step.graph, phase = Phase.Failed, error = step.error.toString())
                }
            }
        }
    }
}
