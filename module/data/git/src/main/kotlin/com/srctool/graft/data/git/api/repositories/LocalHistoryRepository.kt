// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.repositories

import com.srctool.graft.data.git.api.models.ChangedFile
import com.srctool.graft.data.git.api.models.FileVersion
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.Snapshot
import com.srctool.graft.data.git.api.models.SnapshotHit
import com.srctool.graft.data.git.api.models.SnapshotResult
import com.srctool.graft.data.git.api.models.Thinning
import com.srctool.sinew.models.Result
import java.nio.file.Path

/**
 * Local History: automatic snapshots of the working tree, for getting back work that was never committed
 * (Local History). Each snapshot is a commit under `refs/graft/history/<worktree-id>`, built with a temporary index so
 * the user's index is never touched, and never pushed. Error codes are `GIT-R-LHST-…` unless a call says otherwise.
 */
public interface LocalHistoryRepository {
    /**
     * Snapshots the working tree: tracked and untracked files, not ignored ones, not files over the size limit.
     * When the tree equals the last snapshot's nothing is written and that snapshot is returned, unless [label] is
     * given: a labelled snapshot is always made, since it is kept until deleted.
     */
    public suspend fun snapshot(repository: Path, label: String? = null): Result<SnapshotResult>

    /** This worktree's snapshots, newest first. */
    public suspend fun snapshots(repository: Path, limit: Int = Int.MAX_VALUE): Result<List<Snapshot>>

    /** What [snapshot] changed against the one before it (against nothing for the first). */
    public suspend fun changes(repository: Path, snapshot: ObjectId): Result<List<ChangedFile>>

    /** The snapshots in which [path] changed, newest first; a snapshot without the file has a null blob. */
    public suspend fun versions(repository: Path, path: String): Result<List<FileVersion>>

    /** Files [snapshot] has that the working tree no longer has. */
    public suspend fun deletedSince(repository: Path, snapshot: ObjectId): Result<List<String>>

    /** Snapshots in which a path containing [query] (any case) was added, changed or removed. */
    public suspend fun searchPaths(repository: Path, query: String): Result<List<SnapshotHit>>

    /** Snapshots in which the text [query] (exact case) was added to or removed from a file. */
    public suspend fun searchText(repository: Path, query: String): Result<List<SnapshotHit>>

    /** Puts [path] back as [snapshot] had it. The index is not touched. */
    public suspend fun restoreFile(repository: Path, snapshot: ObjectId, path: String): Result<Unit>

    /**
     * Undoes the hunks at positions [hunks] of the diff from [snapshot] to the working file, so those parts are as the
     * snapshot had them (`GIT-R-APLY-PAT` when the file changed since).
     */
    public suspend fun restoreHunks(
        repository: Path,
        snapshot: ObjectId,
        path: String,
        hunks: Set<Int>,
        contextLines: Int = 3,
    ): Result<Unit>

    /**
     * Makes the working tree what [snapshot] was: files are put back, and files made since are removed. A snapshot of
     * the current state is taken first and returned, so the restore itself can be undone.
     */
    public suspend fun restoreAll(repository: Path, snapshot: ObjectId): Result<SnapshotResult>

    /**
     * Removes [path] from every snapshot (Threat Model T16), keeping the chain otherwise as it is. The old objects
     * stay in the repository until `git gc` removes them; this only makes them unreachable.
     */
    public suspend fun purge(repository: Path, path: String): Result<Int>

    /**
     * Applies the retention rules: every snapshot under 24 hours, one an hour to 7 days, one a day to 30, nothing
     * older except labelled ones; then the size budget, oldest unlabelled first. The newest snapshot is always kept.
     * Histories of removed worktrees are dropped 30 days after their last snapshot.
     */
    public suspend fun thin(repository: Path): Result<Thinning>
}
