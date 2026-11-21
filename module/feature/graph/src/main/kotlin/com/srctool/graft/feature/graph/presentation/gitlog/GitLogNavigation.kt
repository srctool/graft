// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.gitlog

import androidx.compose.runtime.Composable
import com.srctool.graft.feature.graph.presentation.rememberDestinationOwner
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.nio.file.Paths

/** The Git log tool window of the repository whose folder git runs in is [repository]. */
public data class GitLogRoute(val repository: String)

@Composable
public fun GitLogDestination(route: GitLogRoute) {
    val viewModel =
        koinViewModel<GitLogViewModel>(viewModelStoreOwner = rememberDestinationOwner(route)) {
            parametersOf(Paths.get(route.repository))
        }
    GitLogPage(viewModel)
}
