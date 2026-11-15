// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.app.api.repositories

import com.srctool.graft.data.app.api.models.RepositoryUiState
import com.srctool.graft.data.app.api.models.Saved
import com.srctool.sinew.models.Result
import java.nio.file.Path

/** Per-repository UI state in `<commonDir>/graft/state.json`; [commonDir] is the repository's `$GIT_COMMON_DIR`. */
public interface RepositoryUiStateRepository {
    public suspend fun read(commonDir: Path): Result<Saved<RepositoryUiState>>

    /** Re-reads, applies [change] and writes; refused for a file saved by a newer Graft. */
    public suspend fun update(
        commonDir: Path,
        change: (RepositoryUiState) -> RepositoryUiState,
    ): Result<RepositoryUiState>
}
