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
import com.srctool.graft.navigation.about.AboutDialog
import com.srctool.graft.navigation.settings.SettingsDialog
import java.nio.file.Path

private enum class HostDialog { None, Settings, About }

/**
 * Temporary (G1 Tasks 17 and 18, until Task 20 builds the main window): Home first, and the repository screen once one
 * is opened, with Mod+T back to Home and Mod+, for Settings. There are no tabs yet, so **Open all** opens the first
 * repository. [repository] draws a repository's screen for its root; the app supplies it because the repository
 * session is the app's. Settings and About are dialogs of this host (D1).
 */
@Composable
public fun TemporaryHost(repository: @Composable (root: Path) -> Unit) {
    var opened by remember { mutableStateOf<Path?>(null) }
    var dialog by remember { mutableStateOf(HostDialog.None) }
    var welcomeRequested by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            val mod = event.isMetaPressed || event.isCtrlPressed
            val down = event.type == KeyEventType.KeyDown && mod
            when {
                down && event.key == Key.T -> {
                    opened = null
                    true
                }

                down && event.key == Key.Comma -> {
                    dialog = HostDialog.Settings
                    true
                }

                else -> false
            }
        },
    ) {
        val root = opened
        if (root == null) {
            HomeDestination(
                onOpen = { opened = it },
                onOpenAll = { opened = it.firstOrNull() },
                onSettings = { dialog = HostDialog.Settings },
                onAbout = { dialog = HostDialog.About },
                welcomeRequested = welcomeRequested,
                onWelcomeHandled = { welcomeRequested = false },
            )
        } else {
            repository(root)
        }
        when (dialog) {
            HostDialog.None -> Unit

            HostDialog.Settings ->
                SettingsDialog(
                    onClose = { dialog = HostDialog.None },
                    // Welcome opens on Home, so a repository that's open is left first.
                    onWelcomeAgain = {
                        dialog = HostDialog.None
                        opened = null
                        welcomeRequested = true
                    },
                )

            HostDialog.About -> AboutDialog(onClose = { dialog = HostDialog.None })
        }
    }
}
