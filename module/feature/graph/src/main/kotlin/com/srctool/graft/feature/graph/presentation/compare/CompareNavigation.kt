// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.compare

import androidx.compose.runtime.Composable
import com.srctool.graft.feature.graph.presentation.rememberDestinationOwner
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.view.RepositoryViewState
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.nio.file.Paths

/** Compare (S7) in one open repository, run in the folder [repository]; it shows the tab's center Compare. */
public data class CompareRoute(val repository: String)

@Composable
public fun CompareDestination(route: CompareRoute, session: RepositorySession, shared: RepositoryViewState) {
    val viewModel =
        koinViewModel<CompareViewModel>(viewModelStoreOwner = rememberDestinationOwner(route)) {
            parametersOf(Paths.get(route.repository), session, shared)
        }
    ComparePage(viewModel)
}
