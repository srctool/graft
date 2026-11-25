// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain.api.usecases

import com.srctool.graft.data.git.api.models.ChangeSelection
import com.srctool.graft.feature.workingcopy.domain.api.models.ChangeRow
import com.srctool.graft.feature.workingcopy.domain.api.models.DiffSide
import com.srctool.graft.feature.workingcopy.domain.api.models.DiscardRecord
import com.srctool.graft.feature.workingcopy.domain.api.models.UndoDiscardOutcome
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingCopy
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingDiff
import com.srctool.sinew.models.Result

/**
 * Moves changes between the working tree and the index, and throws them away (Working Copy and Commit, "Selection
 * model"). Each takes the one [ChangeSelection] commit and shelve take; a stale selection fails with a patch error and
 * changes nothing.
 */
internal interface StageChangesUseCase {
    suspend fun stage(workingCopy: WorkingCopy, selection: ChangeSelection): Result<Unit>

    suspend fun unstage(workingCopy: WorkingCopy, selection: ChangeSelection): Result<Unit>

    /** Discards [selection] after a Local History snapshot, and returns what [undoDiscard] needs. */
    suspend fun discard(workingCopy: WorkingCopy, selection: ChangeSelection): Result<DiscardRecord>

    /**
     * Puts the discarded files back from [record]'s snapshot, unless one was edited since the discard (Task 12
     * wireframe, question 4): then nothing is restored and the outcome names it.
     */
    suspend fun undoDiscard(workingCopy: WorkingCopy, record: DiscardRecord): Result<UndoDiscardOutcome>
}

/** The diff of one file of the Changes panel, in the shape the diff beside it shows. */
internal interface WorkingDiffUseCase {
    /**
     * [row] on [side] (`GIT-R-DIFF-…`). A file over [limit] bytes is not read unless [anyway]; hunk and line positions
     * are those of a diff with the default three lines of context, which is what staging expects.
     */
    suspend fun read(
        workingCopy: WorkingCopy,
        row: ChangeRow,
        side: DiffSide,
        anyway: Boolean = false,
        limit: Long = DEFAULT_LIMIT,
    ): Result<WorkingDiff>

    companion object {
        const val DEFAULT_LIMIT: Long = 2L * 1024 * 1024
    }
}
