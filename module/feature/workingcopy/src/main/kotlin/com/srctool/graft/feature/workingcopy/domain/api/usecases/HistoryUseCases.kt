// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain.api.usecases

import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.SnapshotResult
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingCopy
import com.srctool.sinew.models.Result

/** Snapshot now and the restores (Local History). A restore of everything snapshots first, in the data layer. */
internal interface LocalHistoryUseCase {
    /** A labelled snapshot is always made; an unlabelled one only when the tree changed since the last. */
    suspend fun snapshotNow(workingCopy: WorkingCopy, label: String? = null): Result<SnapshotResult>

    suspend fun restoreFile(workingCopy: WorkingCopy, snapshot: ObjectId, path: String): Result<Unit>

    suspend fun restoreHunks(workingCopy: WorkingCopy, snapshot: ObjectId, path: String, hunks: Set<Int>): Result<Unit>

    /** Returns the snapshot taken of the current state first, so the restore can be undone. */
    suspend fun restoreAll(workingCopy: WorkingCopy, snapshot: ObjectId): Result<SnapshotResult>
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
