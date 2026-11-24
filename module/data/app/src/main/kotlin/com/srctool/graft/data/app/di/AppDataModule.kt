// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.app.di

import com.srctool.graft.core.configuration.AppFolders
import com.srctool.graft.data.app.api.models.AppSettings
import com.srctool.graft.data.app.api.repositories.ChangelistRepository
import com.srctool.graft.data.app.api.repositories.FolderScanRepository
import com.srctool.graft.data.app.api.repositories.KnownRepositoryRepository
import com.srctool.graft.data.app.api.repositories.RepositoryUiStateRepository
import com.srctool.graft.data.app.api.repositories.SettingsRepository
import com.srctool.graft.data.app.api.repositories.WindowStateRepository
import com.srctool.graft.data.app.api.repositories.WorkspaceRepository
import com.srctool.graft.data.app.implementation.repositories.FileChangelistRepository
import com.srctool.graft.data.app.implementation.repositories.FileFolderScanRepository
import com.srctool.graft.data.app.implementation.repositories.FileKnownRepositoryRepository
import com.srctool.graft.data.app.implementation.repositories.FileRepositoryUiStateRepository
import com.srctool.graft.data.app.implementation.repositories.FileSettingsRepository
import com.srctool.graft.data.app.implementation.repositories.FileWindowStateRepository
import com.srctool.graft.data.app.implementation.repositories.FileWorkspaceRepository
import com.srctool.graft.data.app.implementation.source.local.AppStateRecord
import com.srctool.graft.data.app.implementation.source.local.JsonStore
import com.srctool.graft.data.app.implementation.source.local.WorkspacesRecord
import java.time.Instant

/**
 * Graft's own data: `settings.json` and `app-state.json` in [folders]' data folder, workspaces in the default
 * profile's `workspace.json` (moved into real profiles in G3), each repository's `graft/state.json`, and each worktree's `graft/changelists.json`.
 */
public class AppDataModule(folders: AppFolders, clock: () -> Instant = Instant::now) {
    private val appState =
        JsonStore(folders.data.resolve("app-state.json"), 1, AppStateRecord.serializer(), ::AppStateRecord)

    public val settings: SettingsRepository =
        FileSettingsRepository(
            JsonStore(folders.data.resolve("settings.json"), 1, AppSettings.serializer(), ::AppSettings),
        )
    public val knownRepositories: KnownRepositoryRepository = FileKnownRepositoryRepository(appState, clock)
    public val windows: WindowStateRepository = FileWindowStateRepository(appState)
    public val workspaces: WorkspaceRepository =
        FileWorkspaceRepository(
            JsonStore(
                folders.profile(DEFAULT_PROFILE).resolve("workspace.json"),
                1,
                WorkspacesRecord.serializer(),
                ::WorkspacesRecord,
            ),
        )
    public val folderScan: FolderScanRepository = FileFolderScanRepository()
    public val repositoryUiState: RepositoryUiStateRepository = FileRepositoryUiStateRepository()
    public val changelists: ChangelistRepository = FileChangelistRepository()

    private companion object {
        const val DEFAULT_PROFILE = "default"
    }
}
