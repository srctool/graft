// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.spike

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.srctool.graft.data.git.api.repositories.CommitRepository
import com.srctool.graft.feature.graph.domain.implementation.usecases.LaneLayout

/*
 * The spike's navigation definition, the screen's coordinator: the route and its arguments, and the destination that
 * turns them plus the screen's dependencies into a view model and connects it to the page. Registering the route
 * with a navigation library joins this file when :module:navigation picks one (G1).
 */

/** The spike's route: the arguments a navigation library would carry for it. Plain values only. */
public data class GraphSpikeRoute(
    /** The repository folder to lay out. */
    val repository: String,
    /** Rows to scroll in the benchmark after the load; 0 for none. */
    val autoScrollRows: Int = 0,
    /** At most this many commits. */
    val limit: Int = Int.MAX_VALUE,
)

/**
 * Shows [route]: builds its [GraphSpikeViewModel] from the route's arguments, [commits] and the lane layout in a store this
 * destination owns, clears it when the destination leaves, and connects it to the page. [onResults] gets the
 * measurements once every one is in.
 */
@Composable
public fun GraphSpikeDestination(
    route: GraphSpikeRoute,
    commits: CommitRepository,
    startNanos: Long,
    onResults: (SpikeResults) -> Unit = {},
) {
    val store = remember(route) { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    val viewModel =
        remember(store) {
            val factory =
                viewModelFactory { initializer { GraphSpikeViewModel(route, commits, LaneLayout(), startNanos) } }
            ViewModelProvider.create(store, factory)[GraphSpikeViewModel::class]
        }
    GraphSpikePage(viewModel, onResults)
}
