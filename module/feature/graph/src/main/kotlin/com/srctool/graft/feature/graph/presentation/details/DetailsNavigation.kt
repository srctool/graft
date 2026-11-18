// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.srctool.graft.data.git.api.repositories.CommitRepository
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.view.RepositoryViewState
import java.nio.file.Paths

/** Commit details (S5) of one open repository, run in the folder [repository]. */
public data class DetailsRoute(val repository: String)

/** Shows [route]'s commit details for the tab's [shared] selection. */
@Composable
public fun DetailsDestination(
    route: DetailsRoute,
    session: RepositorySession,
    shared: RepositoryViewState,
    commits: CommitRepository,
) {
    val store = remember(route) { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    val viewModel =
        remember(store) {
            val factory =
                viewModelFactory {
                    initializer { DetailsViewModel(Paths.get(route.repository), session, shared, commits) }
                }
            ViewModelProvider.create(store, factory)[DetailsViewModel::class]
        }
    DetailsPage(viewModel)
}
