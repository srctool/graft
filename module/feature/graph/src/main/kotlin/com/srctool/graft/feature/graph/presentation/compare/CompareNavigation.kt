// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.compare

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.srctool.graft.data.git.api.repositories.CommitRepository
import com.srctool.graft.data.git.api.repositories.CompareRepository
import com.srctool.graft.data.git.api.repositories.DiffRepository
import com.srctool.graft.feature.graph.domain.implementation.usecases.GitCompareSummaryUseCase
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.view.RepositoryViewState
import java.nio.file.Paths

/** Compare (S7) in one open repository, run in the folder [repository]; it shows the tab's center Compare. */
public data class CompareRoute(val repository: String)

@Composable
public fun CompareDestination(
    route: CompareRoute,
    session: RepositorySession,
    shared: RepositoryViewState,
    compare: CompareRepository,
    commits: CommitRepository,
    diffs: DiffRepository,
) {
    val store = remember(route) { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    val viewModel =
        remember(store) {
            val factory =
                viewModelFactory {
                    initializer {
                        CompareViewModel(
                            Paths.get(route.repository),
                            session,
                            shared,
                            compare,
                            commits,
                            diffs,
                            GitCompareSummaryUseCase(compare),
                        )
                    }
                }
            ViewModelProvider.create(store, factory)[CompareViewModel::class]
        }
    ComparePage(viewModel)
}
