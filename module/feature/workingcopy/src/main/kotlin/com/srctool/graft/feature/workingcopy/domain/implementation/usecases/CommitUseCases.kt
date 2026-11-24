// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain.implementation.usecases

import com.srctool.graft.data.git.api.models.CommitMessageText
import com.srctool.graft.data.git.api.models.GuardReport
import com.srctool.graft.data.git.api.models.GuardTarget
import com.srctool.graft.data.git.api.models.OperationEntry
import com.srctool.graft.data.git.api.models.UndoState
import com.srctool.graft.data.git.api.repositories.CommittingRepository
import com.srctool.graft.data.git.api.repositories.GuardRepository
import com.srctool.graft.data.git.api.repositories.OperationLogRepository
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingCopy
import com.srctool.graft.feature.workingcopy.domain.api.usecases.CommitChangesUseCase
import com.srctool.graft.feature.workingcopy.domain.api.usecases.CommitOutcome
import com.srctool.graft.feature.workingcopy.domain.api.usecases.CommitRequest
import com.srctool.graft.feature.workingcopy.domain.api.usecases.DraftsUseCase
import com.srctool.graft.feature.workingcopy.domain.api.usecases.UndoRedoUseCase
import com.srctool.sinew.models.Result

internal class GuardedCommitUseCase(
    private val committing: CommittingRepository,
    private val guard: GuardRepository,
    private val drafts: DraftsUseCase,
) : CommitChangesUseCase {
    override suspend fun commit(workingCopy: WorkingCopy, request: CommitRequest): Result<CommitOutcome> {
        val target =
            request.selection?.let { GuardTarget.Selection(it, request.contextLines) } ?: GuardTarget.Staged
        return guarded(workingCopy, request, target) {
            committing.commit(workingCopy.path, request.draft, request.selection, request.contextLines)
        }
    }

    override suspend fun amend(
        workingCopy: WorkingCopy,
        request: CommitRequest,
        withStaged: Boolean,
    ): Result<CommitOutcome> = guarded(workingCopy, request, if (withStaged) GuardTarget.Staged else null) {
        committing.amend(workingCopy.path, request.draft, withStaged)
    }

    override suspend fun previousMessage(workingCopy: WorkingCopy): Result<CommitMessageText?> =
        committing.previousMessage(workingCopy.path)

    override suspend fun template(workingCopy: WorkingCopy): Result<String?> = committing.template(workingCopy.path)

    /** Scan, ask, record, run, then forget the draft; the order is what keeps a refused commit from changing anything. */
    private suspend fun guarded(
        workingCopy: WorkingCopy,
        request: CommitRequest,
        target: GuardTarget?,
        run: suspend () -> Result<com.srctool.graft.data.git.api.models.ObjectId>,
    ): Result<CommitOutcome> {
        if (request.guards && target != null) {
            val report =
                when (val r = guard.scan(workingCopy.path, target, request.guardOptions)) {
                    is Result.Success -> r.data
                    is Result.Failure -> return r
                }
            val open = report.findings.filter { it.fingerprint !in request.allowOnce }
            if (open.isNotEmpty()) return Result.Success(CommitOutcome.Stopped(GuardReport(open, report.allowed)))
            for (finding in report.findings) {
                (guard.allowOnce(workingCopy.path, finding) as? Result.Failure)?.let { return it }
            }
        }
        val id =
            when (val r = run()) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        request.clearDraft?.let {
            // The commit is made; a draft that can't be cleared is not a reason to report it as failed.
            drafts.write(workingCopy, it, "")
        }
        return Result.Success(CommitOutcome.Committed(id))
    }
}

internal class LoggedUndoRedoUseCase(private val operations: OperationLogRepository) : UndoRedoUseCase {
    override suspend fun state(workingCopy: WorkingCopy): Result<UndoState> = operations.undoState(workingCopy.path)

    override suspend fun undo(workingCopy: WorkingCopy): Result<OperationEntry> = operations.undo(workingCopy.path)

    override suspend fun redo(workingCopy: WorkingCopy): Result<OperationEntry> = operations.redo(workingCopy.path)
}
