// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.app.api.repositories

import com.srctool.graft.data.app.api.models.Saved
import com.srctool.graft.data.app.api.models.Workspace
import com.srctool.sinew.models.Result
import java.nio.file.Path

/** Local workspaces, in `profiles/default/workspace.json` until G3 moves them into real profiles. */
public interface WorkspaceRepository {
    public suspend fun list(): Result<Saved<List<Workspace>>>

    /** A new workspace with [repositories] (real paths, duplicates dropped). */
    public suspend fun create(name: String, repositories: List<Path> = emptyList()): Result<Workspace>

    public suspend fun rename(id: String, name: String): Result<Workspace>

    /** Adds [repositories] that aren't in the workspace yet. */
    public suspend fun add(id: String, repositories: List<Path>): Result<Workspace>

    public suspend fun removeRepository(id: String, repository: Path): Result<Workspace>

    public suspend fun delete(id: String): Result<Unit>
}

/** **Scan folder**: the repositories under a folder, found on disk (each is opened through git afterwards). */
public interface FolderScanRepository {
    /**
     * Every folder under [folder] that holds a repository (a `.git` folder or file, or a bare repository), up to
     * [maxDepth] levels down, in folder-tree order (by name, folder by folder). A repository's own subfolders aren't
     * searched (its submodules belong to it), nor are hidden folders; symlinks aren't followed and unreadable folders
     * are skipped.
     */
    public suspend fun scan(folder: Path, maxDepth: Int = 4): Result<List<Path>>
}
