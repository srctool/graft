// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.app.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.srctool.graft.core.git.GitRunnerConfig
import com.srctool.graft.core.git.ProcessGitRunner
import com.srctool.graft.data.git.di.GitDataModule
import com.srctool.graft.feature.graph.presentation.spike.GraphSpike
import java.nio.file.Paths
import kotlin.system.exitProcess

fun main() {
    val startNanos = System.nanoTime()
    // G0 Task 8 spike: GRAFT_SPIKE_REPO opens the graph spike on that repository; removed in G1.
    val spikeRepo = System.getenv("GRAFT_SPIKE_REPO")
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Graft",
            state = rememberWindowState(width = 1280.dp, height = 900.dp),
        ) {
            if (spikeRepo != null) {
                val data = GitDataModule(ProcessGitRunner(GitRunnerConfig()))
                GraphSpike(
                    log = data.commits.log(Paths.get(spikeRepo)),
                    startNanos = startNanos,
                    autoScrollRows = System.getenv("GRAFT_SPIKE_SCROLL")?.toIntOrNull() ?: 0,
                    limit = System.getenv("GRAFT_SPIKE_LIMIT")?.toIntOrNull() ?: Int.MAX_VALUE,
                    onResults = { results ->
                        println("GRAFT_SPIKE_RESULTS $results")
                        if (System.getenv("GRAFT_SPIKE_EXIT") == "1") exitProcess(0)
                    },
                )
            }
        }
    }
}
