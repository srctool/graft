// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.diff

import androidx.compose.runtime.Composable
import com.srctool.graft.feature.graph.presentation.rememberDestinationOwner
import com.srctool.graft.presentation.view.RepositoryViewState
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.nio.file.Paths

/** The diff screen (S6) of one open repository, run in the folder [repository]; it shows the tab's center diff. */
public data class DiffRoute(val repository: String)

@Composable
public fun DiffDestination(route: DiffRoute, shared: RepositoryViewState) {
    val viewModel =
        koinViewModel<DiffScreenViewModel>(viewModelStoreOwner = rememberDestinationOwner(route)) {
            parametersOf(Paths.get(route.repository), shared)
        }
    DiffScreenPage(viewModel)
}
