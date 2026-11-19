// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation

import com.srctool.graft.feature.home.di.homeFeatureKoin
import com.srctool.graft.navigation.about.AboutViewModel
import com.srctool.graft.navigation.about.BuildVersion
import com.srctool.graft.navigation.about.BundledBuildVersion
import com.srctool.graft.navigation.about.BundledLicenseSource
import com.srctool.graft.navigation.about.LicenseSource
import com.srctool.graft.navigation.search.HostActionSearchSource
import com.srctool.graft.navigation.search.SearchViewModel
import com.srctool.graft.navigation.settings.SettingsViewModel
import com.srctool.graft.presentation.search.SearchSource
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * The Koin modules of the features this module hosts (ADR-28), plus the Settings and About hosts' own view models and
 * sources (D1), for the app to start with.
 */
public val navigationKoin: Module =
    module {
        includes(homeFeatureKoin)
        single<LicenseSource> { BundledLicenseSource() }
        single<BuildVersion> { BundledBuildVersion() }
        single<SearchSource>(named("host.actions")) { HostActionSearchSource() }
        viewModel { SettingsViewModel(get()) }
        viewModel { p -> SearchViewModel(getAll<SearchSource>(), p.get()) }
        viewModel { AboutViewModel(install = get(), licenses = get(), build = get()) }
    }
