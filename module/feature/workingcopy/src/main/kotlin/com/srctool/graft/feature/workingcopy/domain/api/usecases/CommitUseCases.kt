// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain.api.usecases

import com.srctool.graft.data.git.api.models.ChangeSelection
import com.srctool.graft.data.git.api.models.CommitDraft
import com.srctool.graft.data.git.api.models.CommitMessageText
import com.srctool.graft.data.git.api.models.GuardOptions
import com.srctool.graft.data.git.api.models.GuardReport
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.OperationEntry
import com.srctool.graft.data.git.api.models.UndoState
import com.srctool.graft.feature.workingcopy.domain.api.models.ChangeRow
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingCopy
import com.srctool.sinew.models.Result

/** What the user asked a commit or an amend to be, with what they answered to the guards. */
internal data class CommitRequest(
    val draft: CommitDraft,
    /** Only these changes are committed; null commits what is in the index. */
    val selection: ChangeSelection? = null,
    /** Commit Guards run unless the repository has them off ("guards off" in the status bar). */
    val guards: Boolean = true,
    val guardOptions: GuardOptions = GuardOptions(),
    /** Fingerprints of findings the user chose to allow once, from the guard dialog. */
    val allowOnce: Set<String> = emptySet(),
    val contextLines: Int = 3,
    /** Forgotten after the commit succeeds, so the box starts empty. */
    val clearDraft: DraftKey? = null,
)

internal sealed interface CommitOutcome {
    data class Committed(val id: ObjectId) : CommitOutcome

    /**
     * A guard fired and nothing was committed. [report] holds only the findings still unanswered; the dialog shows
     * them, and the commit is asked for again with [CommitRequest.allowOnce] for the ones the user lets through.
     */
    data class Stopped(val report: GuardReport) : CommitOutcome
}

/** Commit and amend with Commit Guards in front of them (Working Copy and Commit, "Commit"; Commit Guards). */
internal interface CommitChangesUseCase {
    /**
     * Scans what would be committed, stops when a finding isn't answered, otherwise commits. Findings answered with
     * "allow once" are written to the op log first; if that fails nothing is committed.
     */
    suspend fun commit(workingCopy: WorkingCopy, request: CommitRequest): Result<CommitOutcome>

    /**
     * Replaces the last commit; with [withStaged] the index goes into it too, and then the guards scan the index.
     * Without it only the message changes and there is nothing to scan.
     */
    suspend fun amend(
        workingCopy: WorkingCopy,
        request: CommitRequest,
        withStaged: Boolean = true,
    ): Result<CommitOutcome>

    /** The last commit's message, to start an amend from; null before the first commit. */
    suspend fun previousMessage(workingCopy: WorkingCopy): Result<CommitMessageText?>

    /** `commit.template`'s text, to start the box from; null when none is set. */
    suspend fun template(workingCopy: WorkingCopy): Result<String?>
}

/** Undo and Redo of the last commit or amend (Git Engine, "Operation log"). */
internal interface UndoRedoUseCase {
    /** What each would do now, for the menu's labels and whether they're enabled. */
    suspend fun state(workingCopy: WorkingCopy): Result<UndoState>

    /** `GIT-R-UNDO-MOVD` when the branch has moved since: nothing is changed. */
    suspend fun undo(workingCopy: WorkingCopy): Result<OperationEntry>

    suspend fun redo(workingCopy: WorkingCopy): Result<OperationEntry>
}

/** The selection of everything a list or a section holds, for committing, shelving or discarding it as one. */
internal fun List<ChangeRow>.toSelection(): ChangeSelection = ChangeSelection(
    map { row ->
        val hunks = row.hunks
        com.srctool.graft.data.git.api.models.FileSelection(
            row.path,
            if (hunks == null || !row.partial) {
                com.srctool.graft.data.git.api.models.FileSelection.Scope.WholeFile
            } else {
                com.srctool.graft.data.git.api.models.FileSelection.Scope.Hunks(hunks)
            },
            row.originalPath,
        )
    },
)
