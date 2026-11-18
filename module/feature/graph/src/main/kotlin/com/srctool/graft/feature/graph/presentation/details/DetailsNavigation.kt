// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.details

import androidx.compose.runtime.Composable
import com.srctool.graft.feature.graph.presentation.rememberDestinationOwner
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.view.RepositoryViewState
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.nio.file.Paths

/** Commit details (S5) of one open repository, run in the folder [repository]. */
public data class DetailsRoute(val repository: String)

/** Shows [route]'s commit details for the tab's [shared] selection. */
@Composable
public fun DetailsDestination(route: DetailsRoute, session: RepositorySession, shared: RepositoryViewState) {
    val viewModel =
        koinViewModel<DetailsViewModel>(viewModelStoreOwner = rememberDestinationOwner(route)) {
            parametersOf(Paths.get(route.repository), session, shared)
        }
    DetailsPage(viewModel)
}
