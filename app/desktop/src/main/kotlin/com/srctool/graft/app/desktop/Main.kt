// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.srctool.graft.app.desktop.di.desktopModules
import com.srctool.graft.app.desktop.session.GitRepositorySession
import com.srctool.graft.core.git.WriteQueue
import com.srctool.graft.core.ui.theme.GraftTheme
import com.srctool.graft.core.watcher.FileWatcher
import com.srctool.graft.data.git.api.repositories.RefRepository
import com.srctool.graft.data.git.api.repositories.RepositoryAccessRepository
import com.srctool.graft.feature.graph.presentation.repository.RepositoryScreen
import com.srctool.graft.navigation.TemporaryHost
import com.srctool.sinew.models.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.core.context.startKoin
import java.nio.file.Paths

fun main() {
    // ADR-28: everything the screens need is wired once, here.
    startKoin { modules(desktopModules) }
    // Until Task 20 brings Home and the app navigation, GRAFT_REPO opens the repository screen on that repository.
    val repository = System.getenv("GRAFT_REPO")
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Graft",
            state = rememberWindowState(width = 1280.dp, height = 900.dp),
        ) {
            GraftTheme {
                if (repository != null) {
                    GraphWindow(repository)
                } else {
                    // Home first (G1 Task 17); Task 20's main window replaces this host.
                    TemporaryHost { root -> GraphWindow(root.toString()) }
                }
            }
        }
    }
}

/** Temporary (until Task 20): one repository's session and its screen, wired by hand. */
@Composable
private fun GraphWindow(repository: String) {
    val access = koinInject<RepositoryAccessRepository>()
    val refs = koinInject<RefRepository>()
    val watcher = koinInject<FileWatcher>()
    val writes = koinInject<WriteQueue>()
    var session by remember { mutableStateOf<GitRepositorySession?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(repository) {
        val location = (access.locate(Paths.get(repository)) as? Result.Success)?.data ?: return@LaunchedEffect
        val opened = GitRepositorySession(location, refs, access, watcher, writes)
        // A child of the window's scope: closing the window stops the watcher and git.
        val sessionScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        sessionScope.launch {
            opened.init()
            opened.listen(sessionScope)
        }
        session = opened
    }
    val current = session ?: return
    RepositoryScreen(current)
}
