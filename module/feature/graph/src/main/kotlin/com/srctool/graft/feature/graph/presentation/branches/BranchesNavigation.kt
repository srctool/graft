// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.branches

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.srctool.graft.data.git.api.repositories.StashRepository
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.view.RepositoryViewState
import java.nio.file.Paths

/*
 * The left panel's navigation definition. In G1 it's a section host's content: the repository screen docks it until
 * Task 20's host in :module:navigation collects every feature's sections.
 */

/** The left panel of one open repository, run in the folder [repository]. */
public data class BranchesRoute(val repository: String)

/** Shows [route]'s left panel over the repository [session] and the tab's [shared] state. */
@Composable
public fun BranchesDestination(
    route: BranchesRoute,
    session: RepositorySession,
    shared: RepositoryViewState,
    stashes: StashRepository,
) {
    val store = remember(route) { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    val viewModel =
        remember(store) {
            val factory =
                viewModelFactory {
                    initializer { BranchesViewModel(Paths.get(route.repository), session, shared, stashes) }
                }
            ViewModelProvider.create(store, factory)[BranchesViewModel::class]
        }
    BranchesPage(viewModel)
}
