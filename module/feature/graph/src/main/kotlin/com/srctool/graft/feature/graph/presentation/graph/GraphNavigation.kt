// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.graph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.srctool.graft.data.app.api.repositories.RepositoryUiStateRepository
import com.srctool.graft.data.git.api.repositories.CommitRepository
import com.srctool.graft.feature.graph.domain.implementation.usecases.GitLoadGraphUseCase
import com.srctool.graft.feature.graph.domain.implementation.usecases.GitSearchGraphUseCase
import com.srctool.graft.feature.graph.domain.implementation.usecases.LaneLayout
import com.srctool.graft.presentation.session.RepositorySession
import java.nio.file.Paths

/*
 * The graph screen's navigation definition: its route and the destination that builds the view model from the route
 * and the screen's dependencies. Registering the route with Navigation 3 joins this file in Task 20, when
 * :module:navigation hosts the main window.
 */

/** The graph of one open repository: the folder git runs in (a working tree, or a bare repository's git folder). */
public data class GraphRoute(val repository: String)

/**
 * Shows [route]'s graph: builds its [GraphViewModel] over the repository [session] and the data it needs, in a store
 * this destination owns and clears when it leaves. [onOpenCommit] is Enter on a commit (its full hash).
 */
@Composable
public fun GraphDestination(
    route: GraphRoute,
    session: RepositorySession,
    commits: CommitRepository,
    uiState: RepositoryUiStateRepository,
    onOpenCommit: (String) -> Unit = {},
) {
    val store = remember(route) { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    val viewModel =
        remember(store) {
            val factory =
                viewModelFactory {
                    initializer {
                        GraphViewModel(
                            repository = Paths.get(route.repository),
                            session = session,
                            loadGraph = GitLoadGraphUseCase(commits, ::LaneLayout),
                            searchGraph = GitSearchGraphUseCase(commits),
                            commits = commits,
                            uiState = uiState,
                        )
                    }
                }
            ViewModelProvider.create(store, factory)[GraphViewModel::class]
        }
    GraphPage(viewModel, onOpenCommit)
}
