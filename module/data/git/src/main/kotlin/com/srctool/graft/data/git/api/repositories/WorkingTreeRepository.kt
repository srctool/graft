// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.repositories

import com.srctool.graft.data.git.api.models.WorkingTree
import com.srctool.sinew.models.Result
import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

/** What is changed in a working tree (Working Copy and Commit, "Changes panel"). */
public interface WorkingTreeRepository {
    /**
     * The status of the working tree at [repository] (`GIT-R-STAT-…`): staged, unstaged, untracked and conflicted
     * paths, plus ignored ones when [includeIgnored].
     */
    public suspend fun status(repository: Path, includeIgnored: Boolean = false): Result<WorkingTree>

    /**
     * The status now, and again each time [refreshes] emits (the session's status events, already debounced). A
     * burst of refreshes while a status runs becomes one more run.
     */
    public fun changes(
        repository: Path,
        refreshes: Flow<Unit>,
        includeIgnored: Boolean = false,
    ): Flow<Result<WorkingTree>>
}
