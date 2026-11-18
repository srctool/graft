// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * A view-model store for one destination or dialog, kept while it's shown and cleared when it leaves, so each opening
 * gets a fresh view model. Navigation 3 gives each entry one in Task 20; until then these keep their own.
 */
@Composable
internal fun rememberDestinationOwner(key: Any): ViewModelStoreOwner {
    val owner = remember(key) {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    return owner
}
