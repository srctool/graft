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
import com.srctool.graft.feature.graph.benchmark.SpikeBenchmark
import com.srctool.graft.feature.graph.domain.implementation.usecases.GitLoadGraphUseCase
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
    /** At most this many commits. */
    val limit: Int = Int.MAX_VALUE,
)

/**
 * Shows [route]: builds its [GraphSpikeViewModel] from the route's arguments and the graph-loading use case (over
 * [commits]) in a store this destination owns, clears it when the destination leaves, and connects it to the page.
 * A [benchmark] (spike only) measures the screen from outside.
 */
@Composable
public fun GraphSpikeDestination(route: GraphSpikeRoute, commits: CommitRepository, benchmark: SpikeBenchmark? = null) {
    val store = remember(route) { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    val viewModel =
        remember(store) {
            val factory =
                viewModelFactory {
                    initializer { GraphSpikeViewModel(route, GitLoadGraphUseCase(commits, ::LaneLayout)) }
                }
            ViewModelProvider.create(store, factory)[GraphSpikeViewModel::class]
        }
    GraphSpikePage(viewModel, benchmark)
}
