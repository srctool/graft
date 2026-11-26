// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain.api.usecases

import com.srctool.graft.data.git.api.models.ChangedFile
import com.srctool.graft.data.git.api.models.DeletedFile
import com.srctool.graft.data.git.api.models.FileVersion
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.Snapshot
import com.srctool.graft.data.git.api.models.SnapshotHit
import com.srctool.graft.data.git.api.models.SnapshotResult
import com.srctool.graft.data.git.api.repositories.FileContents
import com.srctool.graft.feature.workingcopy.domain.api.models.HistoryDiff
import com.srctool.graft.feature.workingcopy.domain.api.models.HistorySearch
import com.srctool.graft.feature.workingcopy.domain.api.models.RestoreOutcome
import com.srctool.graft.feature.workingcopy.domain.api.models.RestoreRecord
import com.srctool.graft.feature.workingcopy.domain.api.models.UndoRestoreOutcome
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingCopy
import com.srctool.sinew.models.Result

/**
 * Local History (S11): reading the snapshots, and putting things back. Every restore takes a "before restore" snapshot
 * first and changes nothing if the current content couldn't be got back from it.
 */
internal interface LocalHistoryUseCase {
    /** The snapshots, newest first. */
    suspend fun timeline(workingCopy: WorkingCopy): Result<List<Snapshot>>

    /** What [snapshot] changed against the one before it. */
    suspend fun changes(workingCopy: WorkingCopy, snapshot: ObjectId): Result<List<ChangedFile>>

    /** The snapshots in which [path] changed, newest first. */
    suspend fun versions(workingCopy: WorkingCopy, path: String): Result<List<FileVersion>>

    suspend fun search(workingCopy: WorkingCopy, query: String, kind: HistorySearch): Result<List<SnapshotHit>>

    /** Files the history has and the working copy doesn't. */
    suspend fun deleted(workingCopy: WorkingCopy): Result<List<DeletedFile>>

    /** The file as [snapshot] had it against the file now; over [limit] bytes only when [anyway]. */
    suspend fun diff(
        workingCopy: WorkingCopy,
        snapshot: ObjectId,
        path: String,
        anyway: Boolean = false,
        limit: Long = FileContents.DEFAULT_MAX_BYTES,
    ): Result<HistoryDiff>

    /** A labelled snapshot is always made; an unlabelled one only when the tree changed since the last. */
    suspend fun snapshotNow(workingCopy: WorkingCopy, label: String? = null): Result<SnapshotResult>

    suspend fun restoreFile(workingCopy: WorkingCopy, snapshot: ObjectId, path: String): Result<RestoreOutcome>

    /** [hunks] are positions in the diff [diff] returns. */
    suspend fun restoreHunks(
        workingCopy: WorkingCopy,
        snapshot: ObjectId,
        path: String,
        hunks: Set<Int>,
    ): Result<RestoreOutcome>

    suspend fun restoreAll(workingCopy: WorkingCopy, snapshot: ObjectId): Result<RestoreOutcome>

    suspend fun undo(workingCopy: WorkingCopy, record: RestoreRecord): Result<UndoRestoreOutcome>

    /** Takes [path] out of every snapshot (Threat Model T16); returns how many held it. Can't be undone. */
    suspend fun purge(workingCopy: WorkingCopy, path: String): Result<Int>
}

/** Graft operations that can delete or overwrite uncommitted work, and so need a snapshot before them. */
internal enum class RiskyOperation(val label: String) {
    CHECKOUT("checkout"),
    RESET("reset"),
    DISCARD("discard"),
    PULL("pull"),
    REBASE("rebase"),
    MERGE("merge"),
    CLEAN("clean"),
    UNSHELVE("unshelve"),
    STASH_APPLY("stash apply"),
}

/**
 * What the session calls before a risky operation (Local History, "When a snapshot is taken"). A failure means no
 * snapshot could be taken, and the operation must not run.
 */
internal fun interface BeforeRiskyOperation {
    suspend fun beforeRisky(workingCopy: WorkingCopy, operation: RiskyOperation): Result<Unit>
}
