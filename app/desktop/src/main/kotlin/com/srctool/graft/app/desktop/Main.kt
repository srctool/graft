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
import com.srctool.graft.app.desktop.session.GitRepositorySession
import com.srctool.graft.core.configuration.AppFolders
import com.srctool.graft.core.git.GitRunnerConfig
import com.srctool.graft.core.git.ProcessGitRunner
import com.srctool.graft.core.git.WriteQueue
import com.srctool.graft.core.ui.theme.GraftTheme
import com.srctool.graft.core.watcher.NativeFileWatcher
import com.srctool.graft.data.app.di.AppDataModule
import com.srctool.graft.data.git.di.GitDataModule
import com.srctool.graft.feature.graph.presentation.repository.RepositoryData
import com.srctool.graft.feature.graph.presentation.repository.RepositoryScreen
import com.srctool.sinew.models.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.file.Paths

fun main() {
    // Until Task 20 brings Home and the app navigation, GRAFT_REPO opens the repository screen on that repository.
    val repository = System.getenv("GRAFT_REPO")
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Graft",
            state = rememberWindowState(width = 1280.dp, height = 900.dp),
        ) {
            GraftTheme {
                if (repository != null) GraphWindow(repository)
            }
        }
    }
}

/** Temporary (until Task 20): one repository's session and its screen, wired by hand. */
@Composable
private fun GraphWindow(repository: String) {
    val git = remember { GitDataModule(ProcessGitRunner(GitRunnerConfig())) }
    val app = remember { AppDataModule(AppFolders.current()) }
    var session by remember { mutableStateOf<GitRepositorySession?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(repository) {
        val location = (git.access.locate(Paths.get(repository)) as? Result.Success)?.data ?: return@LaunchedEffect
        val opened = GitRepositorySession(location, git.refs, git.access, NativeFileWatcher(), WriteQueue())
        // A child of the window's scope: closing the window stops the watcher and git.
        val sessionScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        sessionScope.launch {
            opened.init()
            opened.listen(sessionScope)
        }
        session = opened
    }
    val current = session ?: return
    val data = remember { RepositoryData(git.commits, git.stashes, git.diffs, app.repositoryUiState, app.settings) }
    RepositoryScreen(current, data)
}
