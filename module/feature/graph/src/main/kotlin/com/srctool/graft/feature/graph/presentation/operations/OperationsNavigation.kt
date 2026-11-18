// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.operations

import androidx.compose.runtime.Composable
import com.srctool.graft.feature.graph.presentation.rememberDestinationOwner
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.view.RepositoryViewState
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.nio.file.Paths

/** The Operations tool window (S8, the reflog timeline) of one open repository, run in the folder [repository]. */
public data class OperationsRoute(val repository: String)

@Composable
public fun OperationsDestination(route: OperationsRoute, session: RepositorySession, shared: RepositoryViewState) {
    val viewModel =
        koinViewModel<OperationsViewModel>(viewModelStoreOwner = rememberDestinationOwner(route)) {
            parametersOf(Paths.get(route.repository), session, shared)
        }
    OperationsPage(viewModel)
}
