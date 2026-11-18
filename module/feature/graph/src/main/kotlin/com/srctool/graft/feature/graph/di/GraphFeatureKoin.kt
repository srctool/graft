// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.di

import com.srctool.graft.feature.graph.domain.api.usecases.CompareSummaryUseCase
import com.srctool.graft.feature.graph.domain.api.usecases.LoadGraphUseCase
import com.srctool.graft.feature.graph.domain.api.usecases.ReflogTimelineUseCase
import com.srctool.graft.feature.graph.domain.api.usecases.SearchGraphUseCase
import com.srctool.graft.feature.graph.domain.implementation.usecases.GitCompareSummaryUseCase
import com.srctool.graft.feature.graph.domain.implementation.usecases.GitLoadGraphUseCase
import com.srctool.graft.feature.graph.domain.implementation.usecases.GitReflogTimelineUseCase
import com.srctool.graft.feature.graph.domain.implementation.usecases.GitSearchGraphUseCase
import com.srctool.graft.feature.graph.domain.implementation.usecases.LaneLayout
import com.srctool.graft.feature.graph.presentation.branches.BranchesViewModel
import com.srctool.graft.feature.graph.presentation.compare.CompareViewModel
import com.srctool.graft.feature.graph.presentation.details.DetailsViewModel
import com.srctool.graft.feature.graph.presentation.diff.DiffScreenViewModel
import com.srctool.graft.feature.graph.presentation.graph.GraphViewModel
import com.srctool.graft.feature.graph.presentation.operations.OperationsViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * The graph feature for Koin (ADR-28): its use cases, and its view models. A view model's per-tab objects come as
 * parameters, in this order: the folder git runs in (`Path`), the tab's `RepositorySession` (except the diff screen),
 * and its `RepositoryViewState`. The data modules' Koin modules provide the repositories.
 */
public val graphFeatureKoin: Module =
    module {
        factory<LoadGraphUseCase> { GitLoadGraphUseCase(get(), ::LaneLayout) }
        factory<SearchGraphUseCase> { GitSearchGraphUseCase(get()) }
        factory<CompareSummaryUseCase> { GitCompareSummaryUseCase(get()) }
        factory<ReflogTimelineUseCase> { GitReflogTimelineUseCase(get(), get()) }

        viewModel { p -> GraphViewModel(p.get(), p.get(), get(), get(), get(), get(), p.get()) }
        viewModel { p -> BranchesViewModel(p.get(), p.get(), p.get(), get()) }
        viewModel { p -> DetailsViewModel(p.get(), p.get(), p.get(), get()) }
        viewModel { p -> CompareViewModel(p.get(), p.get(), p.get(), get(), get(), get(), get()) }
        viewModel { p -> DiffScreenViewModel(p.get(), p.get(), get(), get()) }
        viewModel { p -> OperationsViewModel(p.get(), p.get(), p.get(), get()) }
    }
