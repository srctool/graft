// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.app.di

import com.srctool.graft.data.app.api.repositories.ChangelistRepository
import com.srctool.graft.data.app.api.repositories.FolderScanRepository
import com.srctool.graft.data.app.api.repositories.KnownRepositoryRepository
import com.srctool.graft.data.app.api.repositories.RepositoryUiStateRepository
import com.srctool.graft.data.app.api.repositories.SettingsRepository
import com.srctool.graft.data.app.api.repositories.WindowStateRepository
import com.srctool.graft.data.app.api.repositories.WorkspaceRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * This module's repositories for Koin (ADR-28), each bound to its `api` interface. The app's Koin setup provides the
 * `AppFolders`; [AppDataModule] builds the implementations.
 */
public fun appDataKoin(): Module = module {
    single { AppDataModule(get()) }
    single<SettingsRepository> { get<AppDataModule>().settings }
    single<KnownRepositoryRepository> { get<AppDataModule>().knownRepositories }
    single<WindowStateRepository> { get<AppDataModule>().windows }
    single<WorkspaceRepository> { get<AppDataModule>().workspaces }
    single<FolderScanRepository> { get<AppDataModule>().folderScan }
    single<RepositoryUiStateRepository> { get<AppDataModule>().repositoryUiState }
    single<ChangelistRepository> { get<AppDataModule>().changelists }
}
