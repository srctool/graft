// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.diff

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.srctool.graft.data.app.api.repositories.SettingsRepository
import com.srctool.graft.data.git.api.repositories.DiffRepository
import com.srctool.graft.presentation.view.RepositoryViewState
import java.nio.file.Paths

/** The diff screen (S6) of one open repository, run in the folder [repository]; it shows the tab's center diff. */
public data class DiffRoute(val repository: String)

@Composable
public fun DiffDestination(
    route: DiffRoute,
    shared: RepositoryViewState,
    diffs: DiffRepository,
    settings: SettingsRepository,
) {
    val store = remember(route) { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    val viewModel =
        remember(store) {
            val factory =
                viewModelFactory {
                    initializer { DiffScreenViewModel(Paths.get(route.repository), shared, diffs, settings) }
                }
            ViewModelProvider.create(store, factory)[DiffScreenViewModel::class]
        }
    DiffScreenPage(viewModel)
}
