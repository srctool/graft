// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.app.implementation.repositories

import com.srctool.graft.data.app.api.models.RepositoryUiState
import com.srctool.graft.data.app.api.models.Saved
import com.srctool.graft.data.app.api.repositories.RepositoryUiStateRepository
import com.srctool.graft.data.app.implementation.source.local.JsonStore
import com.srctool.graft.data.app.implementation.source.local.RepositoryStateRecord
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.map
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal class FileRepositoryUiStateRepository : RepositoryUiStateRepository {
    // One store per repository, so every worktree's tab shares its lock.
    private val stores = ConcurrentHashMap<Path, JsonStore<RepositoryStateRecord>>()

    override suspend fun read(commonDir: Path): Result<Saved<RepositoryUiState>> =
        store(commonDir).read().map { Saved(it.value.toState(), it.status) }

    override suspend fun update(
        commonDir: Path,
        change: (RepositoryUiState) -> RepositoryUiState,
    ): Result<RepositoryUiState> = store(commonDir).update { change(it.toState()).toRecord() }.map { it.toState() }

    private fun store(commonDir: Path) = stores.computeIfAbsent(commonDir.toAbsolutePath().normalize()) {
        JsonStore(
            it.resolve("graft").resolve("state.json"),
            VERSION,
            RepositoryStateRecord.serializer(),
            ::RepositoryStateRecord,
        )
    }

    private fun RepositoryStateRecord.toState() =
        RepositoryUiState(hiddenRefs.toSet(), soloRefs.toSet(), pinnedRefs.toSet())

    private fun RepositoryUiState.toRecord() =
        RepositoryStateRecord(hiddenRefs.sorted(), soloRefs.sorted(), pinnedRefs.sorted())

    private companion object {
        const val VERSION = 1
    }
}
