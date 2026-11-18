// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.graph

import androidx.compose.runtime.Composable
import com.srctool.graft.feature.graph.presentation.rememberDestinationOwner
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.view.RepositoryViewState
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.nio.file.Paths

/*
 * The graph screen's navigation definition: its route and the destination that gets its view model from Koin
 * (ADR-28). Registering the route with Navigation 3 joins this file in Task 20, when :module:navigation hosts the
 * main window.
 */

/** The graph of one open repository: the folder git runs in (a working tree, or a bare repository's git folder). */
public data class GraphRoute(val repository: String)

/**
 * Shows [route]'s graph over the repository [session] and the tab's shared [view] state. [onOpenCommit] is Enter on a
 * commit (its full hash).
 */
@Composable
public fun GraphDestination(
    route: GraphRoute,
    session: RepositorySession,
    view: RepositoryViewState,
    onOpenCommit: (String) -> Unit = {},
) {
    val viewModel =
        koinViewModel<GraphViewModel>(viewModelStoreOwner = rememberDestinationOwner(route)) {
            parametersOf(Paths.get(route.repository), session, view)
        }
    GraphPage(viewModel, onOpenCommit)
}
