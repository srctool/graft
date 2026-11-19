// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.search

import com.srctool.graft.data.app.api.models.RepositoryUiState
import com.srctool.graft.data.app.api.models.Saved
import com.srctool.graft.data.app.api.models.SavedStatus
import com.srctool.graft.data.app.api.repositories.RepositoryUiStateRepository
import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.presentation.search.RepositoryScope
import com.srctool.graft.presentation.session.RepoSnapshot
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.session.SnapshotPart
import com.srctool.graft.presentation.view.RepositoryViewState
import com.srctool.sinew.models.Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/** A repository scope for tests whose fake sources never read it. */
internal object RepositoryScopeStub {
    val location = RepositoryLocation(Path.of("/repo"), Path.of("/repo/.git"), Path.of("/repo/.git"))

    val session: RepositorySession =
        object : RepositorySession {
            override val location = RepositoryScopeStub.location
            override val snapshot: StateFlow<RepoSnapshot> = MutableStateFlow(RepoSnapshot(location))

            override fun refresh(parts: Set<SnapshotPart>) = Unit

            override suspend fun <T> write(block: suspend () -> T): T = block()
        }

    private val store =
        object : RepositoryUiStateRepository {
            override suspend fun read(commonDir: Path): Result<Saved<RepositoryUiState>> =
                Result.Success(Saved(RepositoryUiState(), SavedStatus.Current))

            override suspend fun update(
                commonDir: Path,
                change: (RepositoryUiState) -> RepositoryUiState,
            ): Result<RepositoryUiState> = Result.Success(change(RepositoryUiState()))
        }

    val instance: RepositoryScope = RepositoryScope(session, RepositoryViewState(location.commonDir, store))
}
