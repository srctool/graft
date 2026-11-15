// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.domain.api.usecases

import com.srctool.graft.data.app.api.models.Workspace
import com.srctool.graft.data.git.api.models.CloneProgress
import com.srctool.graft.data.git.api.models.CloneRequest
import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.feature.home.domain.api.models.OpenOutcome
import com.srctool.graft.feature.home.domain.api.models.ScannedRepository
import com.srctool.sinew.models.Result
import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

/** **Open / add existing**, a folder dropped on the window, and `graft open <path>`. */
internal interface OpenRepositoryUseCase {
    /**
     * Finds the repository [folder] is in and records it in Home's recent list (one entry per real path, so a symlink
     * or a subfolder opens the same repository). Never throws; every case is an [OpenOutcome].
     */
    suspend fun open(folder: Path): OpenOutcome

    /**
     * **Trust this folder**, after the user saw [OpenOutcome.Untrusted.command]: adds [untrusted] to the global
     * `safe.directory` list, then opens [folder] again.
     */
    suspend fun trustAndOpen(untrusted: OpenOutcome.Untrusted, folder: Path): OpenOutcome
}

/** **Scan folder**: the repositories under a folder, then optionally a workspace of them. */
internal interface ScanFolderUseCase {
    /** What's under [folder], in folder-tree order, each marked when Home already lists it. */
    suspend fun scan(folder: Path): Result<List<ScannedRepository>>

    /** A workspace named [name] of [repositories]; they appear in Home under it, not in the recent list. */
    suspend fun addAsWorkspace(name: String, repositories: List<Path>): Result<Workspace>
}

/** **Clone**. */
internal interface CloneRepositoryUseCase {
    /**
     * Git's progress while it clones, then [CloneProgress.Finished], after which the clone is in Home's recent list.
     * A failure is thrown to the collector (`GIT-R-CLON-…`); cancelling stops git.
     */
    fun clone(request: CloneRequest): Flow<CloneProgress>
}

/** **Init**. */
internal interface InitRepositoryUseCase {
    /** `git init` in [folder] on [defaultBranch], then the new repository is in Home's recent list. */
    suspend fun init(folder: Path, defaultBranch: String = "main"): Result<RepositoryLocation>
}
