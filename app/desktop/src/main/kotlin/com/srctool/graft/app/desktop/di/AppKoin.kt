// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.app.desktop.di

import com.srctool.graft.core.configuration.AppFolders
import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.core.git.GitRunnerConfig
import com.srctool.graft.core.git.ProcessGitRunner
import com.srctool.graft.core.git.WriteQueue
import com.srctool.graft.core.watcher.FileWatcher
import com.srctool.graft.core.watcher.NativeFileWatcher
import com.srctool.graft.data.app.di.appDataKoin
import com.srctool.graft.data.git.di.gitDataKoin
import com.srctool.graft.feature.graph.di.graphFeatureKoin
import com.srctool.graft.navigation.navigationKoin
import org.koin.core.module.Module
import org.koin.dsl.module

/** What the app itself provides (ADR-28): the git runner, Graft's folders, the write queue and the file watcher. */
internal val appKoin: Module =
    module {
        single<GitRunner> { ProcessGitRunner(GitRunnerConfig()) }
        single { AppFolders.current() }
        single { WriteQueue() }
        single<FileWatcher> { NativeFileWatcher() }
    }

/** Every Koin module the desktop app starts with: its own, the data modules', the features'. */
internal val desktopModules: List<Module> = listOf(appKoin, gitDataKoin, appDataKoin, graphFeatureKoin, navigationKoin)
