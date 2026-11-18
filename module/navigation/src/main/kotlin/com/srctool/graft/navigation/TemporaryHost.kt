// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.srctool.graft.feature.home.presentation.home.HomeDestination
import java.nio.file.Path

/**
 * Temporary (G1 Task 17, until Task 20 builds the main window): Home first, and the repository screen once one is
 * opened, with Mod+T back to Home. There are no tabs yet, so **Open all** opens the first repository. [repository]
 * draws a repository's screen for its root; the app supplies it because the repository session is the app's.
 */
@Composable
public fun TemporaryHost(repository: @Composable (root: Path) -> Unit) {
    var opened by remember { mutableStateOf<Path?>(null) }
    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            val home =
                event.type == KeyEventType.KeyDown && event.key == Key.T && (event.isMetaPressed || event.isCtrlPressed)
            if (home) opened = null
            home
        },
    ) {
        val root = opened
        if (root == null) {
            HomeDestination(onOpen = { opened = it }, onOpenAll = { opened = it.firstOrNull() })
        } else {
            repository(root)
        }
    }
}
