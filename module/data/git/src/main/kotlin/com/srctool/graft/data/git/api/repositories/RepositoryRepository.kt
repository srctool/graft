// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.repositories

import com.srctool.graft.data.git.api.models.CloneProgress
import com.srctool.graft.data.git.api.models.CloneRequest
import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.data.git.api.models.RepositorySummary
import com.srctool.sinew.models.Result
import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

/** Getting repositories into Graft: finding them, summarizing them, creating and cloning them. */
public interface RepositoryRepository {
    /**
     * The repository [folder] belongs to (any folder inside it, its `.git`, a symlink to it). Fails with
     * `GIT-R-OPEN-NF` when it isn't in one or doesn't exist, and `GIT-R-OPEN-OWN` (Graft's `GitUnsafeOwnerException`)
     * when git refuses a repository another user owns.
     */
    public suspend fun locate(folder: Path): Result<RepositoryLocation>

    /** HEAD, upstream and whether there are changes, for the Home screen and tabs (`GIT-R-STAT-…`). */
    public suspend fun summary(workTree: Path): Result<RepositorySummary>

    /** A new repository in [folder] (created if missing) on [defaultBranch] (`GIT-R-INIT-…`). */
    public suspend fun init(folder: Path, defaultBranch: String = "main"): Result<RepositoryLocation>

    /**
     * Clones as the flow is collected: git's progress, then [CloneProgress.Finished]. A failure is thrown to the
     * collector as `GitException` (`GIT-R-CLON-…`); cancelling stops git. Graft's environment applies, so git never
     * prompts: a credential helper may answer, nothing else.
     */
    public fun clone(request: CloneRequest): Flow<CloneProgress>

    /**
     * Adds [folder] to the global `safe.directory` list, for **Trust this folder** after a `GIT-R-OPEN-OWN` failure.
     * Writes `~/.gitconfig`: callers show the change first (Threat Model, config writing).
     */
    public suspend fun trust(folder: String): Result<Unit>
}
