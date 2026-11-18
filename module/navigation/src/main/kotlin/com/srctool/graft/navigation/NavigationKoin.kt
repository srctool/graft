// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation

import com.srctool.graft.feature.home.di.homeFeatureKoin
import org.koin.core.module.Module
import org.koin.dsl.module

/** The Koin modules of the features this module hosts (ADR-28), for the app to start with. */
public val navigationKoin: Module = module { includes(homeFeatureKoin) }
