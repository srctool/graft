// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.di

import com.srctool.graft.feature.home.domain.api.usecases.CloneRepositoryUseCase
import com.srctool.graft.feature.home.domain.api.usecases.InitRepositoryUseCase
import com.srctool.graft.feature.home.domain.api.usecases.InitTemplates
import com.srctool.graft.feature.home.domain.api.usecases.OpenRepositoryUseCase
import com.srctool.graft.feature.home.domain.api.usecases.RepositorySummaryUseCase
import com.srctool.graft.feature.home.domain.api.usecases.ScanFolderUseCase
import com.srctool.graft.feature.home.domain.implementation.usecases.BundledInitTemplates
import com.srctool.graft.feature.home.domain.implementation.usecases.GitCloneRepositoryUseCase
import com.srctool.graft.feature.home.domain.implementation.usecases.GitInitRepositoryUseCase
import com.srctool.graft.feature.home.domain.implementation.usecases.GitOpenRepositoryUseCase
import com.srctool.graft.feature.home.domain.implementation.usecases.GitRepositorySummaryUseCase
import com.srctool.graft.feature.home.domain.implementation.usecases.GitScanFolderUseCase
import com.srctool.graft.feature.home.presentation.clone.CloneViewModel
import com.srctool.graft.feature.home.presentation.home.HomeViewModel
import com.srctool.graft.feature.home.presentation.initrepo.InitViewModel
import com.srctool.graft.feature.home.presentation.onboarding.OnboardingViewModel
import com.srctool.graft.feature.home.search.HomeActionSearchSource
import com.srctool.graft.feature.home.search.RepositorySearchSource
import com.srctool.graft.presentation.search.SearchSource
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * The home feature for Koin (ADR-28): its use cases and view models. The summaries' four-at-a-time limit is one
 * `single`, so every row of every list shares it. A dialog's optional folder comes as a parameter (`Path?`).
 */
public val homeFeatureKoin: Module =
    module {
        factory<OpenRepositoryUseCase> { GitOpenRepositoryUseCase(get(), get()) }
        factory<ScanFolderUseCase> { GitScanFolderUseCase(get(), get(), get()) }
        factory<CloneRepositoryUseCase> { GitCloneRepositoryUseCase(get(), get()) }
        factory<InitRepositoryUseCase> { GitInitRepositoryUseCase(get(), get(), get()) }
        single<RepositorySummaryUseCase> { GitRepositorySummaryUseCase(get()) }
        single<InitTemplates> { BundledInitTemplates }

        single<SearchSource>(named("home.repositories")) { RepositorySearchSource(get()) }
        single<SearchSource>(named("home.actions")) { HomeActionSearchSource() }

        viewModel { HomeViewModel(get(), get(), get(), get(), get(), get()) }
        viewModel { OnboardingViewModel(install = get(), identity = get(), settings = get()) }
        viewModel { p -> CloneViewModel(folder = p.getOrNull(), clone = get()) }
        viewModel { p -> InitViewModel(folder = p.getOrNull(), init = get(), identity = get(), templates = get()) }
    }
