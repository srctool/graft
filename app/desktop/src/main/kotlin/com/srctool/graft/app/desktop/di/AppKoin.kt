// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.app.desktop.di

import com.srctool.graft.app.desktop.session.GitRepositorySession
import com.srctool.graft.app.desktop.session.RepositorySessionsService
import com.srctool.graft.core.configuration.AppFolders
import com.srctool.graft.core.git.CommandLog
import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.core.git.GitRunnerConfig
import com.srctool.graft.core.git.ProcessGitRunner
import com.srctool.graft.core.git.WriteQueue
import com.srctool.graft.core.watcher.FileWatcher
import com.srctool.graft.core.watcher.NativeFileWatcher
import com.srctool.graft.data.app.di.appDataKoin
import com.srctool.graft.data.git.api.repositories.RefRepository
import com.srctool.graft.data.git.api.repositories.RepositoryAccessRepository
import com.srctool.graft.data.git.di.gitDataKoin
import com.srctool.graft.navigation.navigationKoin
import com.srctool.graft.presentation.session.RepositorySessions
import com.srctool.graft.presentation.window.WindowState
import org.koin.core.module.Module
import org.koin.dsl.module

/** What the app itself provides (ADR-28): the git runner, Graft's folders, the write queue and the file watcher. */
internal fun appKoin(): Module = module {
    single { CommandLog() }
    single<GitRunner> { ProcessGitRunner(GitRunnerConfig(commandLog = get())) }
    single { AppFolders.current() }
    single { WriteQueue() }
    single<FileWatcher> { NativeFileWatcher() }
    // The windows and tabs, and one session per repository that some tab shows (Architecture, "Repository session").
    single { WindowState() }
    single {
        val refs = get<RefRepository>()
        val access = get<RepositoryAccessRepository>()
        val watcher = get<FileWatcher>()
        val writes = get<WriteQueue>()
        RepositorySessionsService(get()) { GitRepositorySession(it, refs, access, watcher, writes) }
    }
    single<RepositorySessions> { get<RepositorySessionsService>() }
}

/** Every Koin module the desktop app starts with: its own, the data modules', the features'. */
internal fun desktopModules(): List<Module> = listOf(appKoin(), gitDataKoin(), appDataKoin(), navigationKoin())
