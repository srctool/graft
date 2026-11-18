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
public interface RepositoryAccessRepository {
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
     * Init's first commit: stages exactly [paths] (relative to [workTree], taken literally) and commits them with
     * [message] (`GIT-R-STAG-…`, `GIT-R-CMIT-…`). The author is the identity git reads for the repository, so a missing
     * one fails with git's own message. A brand-new repository has no other writer, so this doesn't use a session's
     * write queue.
     */
    public suspend fun initialCommit(workTree: Path, paths: List<String>, message: String): Result<Unit>

    /**
     * Clones as the flow is collected: git's progress, then [CloneProgress.Finished]. A failure is thrown to the
     * collector as `GitException` (`GIT-R-CLON-…`); cancelling stops git and everything it started, and removes the
     * folder this clone created (a folder that was there before is left as it was). Graft's environment applies, so git never
     * prompts: a credential helper may answer, nothing else.
     */
    public fun clone(request: CloneRequest): Flow<CloneProgress>

    /**
     * Adds [folder] to the global `safe.directory` list, for **Trust this folder** after a `GIT-R-OPEN-OWN` failure.
     * Writes `~/.gitconfig`: callers show the change first (Threat Model, config writing).
     */
    public suspend fun trust(folder: String): Result<Unit>

    /**
     * The folder to pass to [trust] when [error] is git refusing a repository another user owns (`GIT-R-…-OWN`):
     * exactly as git named it, since `safe.directory` must match git's spelling. Null for any other error, or when git
     * didn't name it.
     */
    public fun untrustedFolder(error: Throwable): String?
}
