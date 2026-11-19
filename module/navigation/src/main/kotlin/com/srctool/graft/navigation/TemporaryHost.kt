// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.srctool.graft.data.git.api.models.LogQuery
import com.srctool.graft.data.git.api.models.LogScope
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.repositories.CommitRepository
import com.srctool.graft.feature.home.presentation.home.HomeDestination
import com.srctool.graft.feature.home.search.HomeActions
import com.srctool.graft.feature.home.search.HomeCommand
import com.srctool.graft.navigation.about.AboutDialog
import com.srctool.graft.navigation.search.DoubleShift
import com.srctool.graft.navigation.search.HostActions
import com.srctool.graft.navigation.search.SearchDialog
import com.srctool.graft.navigation.search.SearchTab
import com.srctool.graft.navigation.settings.SettingsDialog
import com.srctool.graft.presentation.search.RepositoryScope
import com.srctool.graft.presentation.search.SearchScope
import com.srctool.graft.presentation.search.SearchTarget
import com.srctool.graft.presentation.view.CenterView
import com.srctool.graft.presentation.view.ViewSelection
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.nio.file.Path

private enum class HostDialog { None, Settings, About, Search, Actions }

/**
 * Temporary (G1 Tasks 17 to 19, until Task 20 builds the main window): Home first, and the repository screen once one
 * is opened, with Mod+T back to Home, Mod+, for Settings, double Shift for Search Everywhere and Mod+Shift+A for Find
 * Action. There are no tabs yet, so **Open all** opens the first repository. [repository] draws a repository's screen
 * for its root and reports the repository's session and shared state through its second argument (Search Everywhere
 * reads and moves them); the app supplies it because the repository session is the app's. Settings, About and Search
 * are dialogs of this host (D1).
 */
@Composable
public fun TemporaryHost(repository: @Composable (root: Path, onScope: (RepositoryScope?) -> Unit) -> Unit) {
    var opened by remember { mutableStateOf<Path?>(null) }
    var dialog by remember { mutableStateOf(HostDialog.None) }
    var welcomeRequested by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf<HomeCommand?>(null) }
    var scope by remember { mutableStateOf<RepositoryScope?>(null) }
    val doubleShift = remember { DoubleShift() }
    val commits = koinInject<CommitRepository>()
    val coroutines = rememberCoroutineScope()

    fun goHome() {
        opened = null
        scope = null
    }

    fun select(id: ObjectId) {
        scope?.view?.let {
            it.center.value = CenterView.Graph
            it.selection.value = ViewSelection.Commit(id)
        }
    }

    fun activate(target: SearchTarget) {
        when (target) {
            is SearchTarget.OpenRepository -> opened = target.root

            is SearchTarget.SelectCommit -> select(target.id)

            // The newest commit that changed the file: G1 has no file viewer, so its change is what there is to show.
            is SearchTarget.FileInHead -> {
                val folder = scope?.session?.location?.let { it.workTree ?: it.gitDir } ?: return
                coroutines.launch {
                    val log = commits.search(folder, LogQuery(path = target.path), LogScope.All())
                    log.rows.firstOrNull()?.let { select(it.id) }
                }
            }

            is SearchTarget.RunAction ->
                when (target.id) {
                    HostActions.SETTINGS -> dialog = HostDialog.Settings

                    HostActions.ABOUT -> dialog = HostDialog.About

                    HostActions.WELCOME -> {
                        goHome()
                        welcomeRequested = true
                    }

                    HostActions.HOME -> goHome()

                    else -> HomeActions.command(target.id)?.let {
                        // Home's own commands run on Home.
                        goHome()
                        command = it
                    }
                }
        }
    }

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val mod = event.isMetaPressed || event.isCtrlPressed
            val shift = event.key == Key.ShiftLeft || event.key == Key.ShiftRight
            if (doubleShift.onKeyDown(shift, mod || event.isAltPressed, System.currentTimeMillis())) {
                dialog = HostDialog.Search
                return@onPreviewKeyEvent true
            }
            when {
                mod && event.key == Key.T -> {
                    goHome()
                    true
                }

                mod && event.key == Key.Comma -> {
                    dialog = HostDialog.Settings
                    true
                }

                mod && event.isShiftPressed && event.key == Key.A -> {
                    dialog = HostDialog.Actions
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
                onSearch = { dialog = HostDialog.Search },
                welcomeRequested = welcomeRequested,
                onWelcomeHandled = { welcomeRequested = false },
                command = command,
                onCommandHandled = { command = null },
            )
        } else {
            repository(root) { scope = it }
        }
        when (dialog) {
            HostDialog.None -> Unit

            HostDialog.Settings ->
                SettingsDialog(
                    onClose = { dialog = HostDialog.None },
                    // Welcome opens on Home, so a repository that's open is left first.
                    onWelcomeAgain = {
                        dialog = HostDialog.None
                        goHome()
                        welcomeRequested = true
                    },
                )

            HostDialog.About -> AboutDialog(onClose = { dialog = HostDialog.None })

            HostDialog.Search, HostDialog.Actions ->
                SearchDialog(
                    scope = SearchScope(scope),
                    startTab = if (dialog == HostDialog.Actions) SearchTab.Actions else SearchTab.All,
                    onActivate = ::activate,
                    onClose = { dialog = HostDialog.None },
                )
        }
    }
}
