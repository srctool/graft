// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain.implementation.usecases

import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.SnapshotResult
import com.srctool.graft.data.git.api.repositories.LocalHistoryRepository
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingCopy
import com.srctool.graft.feature.workingcopy.domain.api.usecases.BeforeRiskyOperation
import com.srctool.graft.feature.workingcopy.domain.api.usecases.LocalHistoryUseCase
import com.srctool.graft.feature.workingcopy.domain.api.usecases.RiskyOperation
import com.srctool.sinew.models.Result

internal class SnapshotUseCase(private val history: LocalHistoryRepository) :
    LocalHistoryUseCase,
    BeforeRiskyOperation {
    override suspend fun snapshotNow(workingCopy: WorkingCopy, label: String?): Result<SnapshotResult> =
        history.snapshot(workingCopy.path, label?.trim()?.ifEmpty { null })

    override suspend fun restoreFile(workingCopy: WorkingCopy, snapshot: ObjectId, path: String): Result<Unit> =
        history.restoreFile(workingCopy.path, snapshot, path)

    override suspend fun restoreHunks(
        workingCopy: WorkingCopy,
        snapshot: ObjectId,
        path: String,
        hunks: Set<Int>,
    ): Result<Unit> = history.restoreHunks(workingCopy.path, snapshot, path, hunks)

    override suspend fun restoreAll(workingCopy: WorkingCopy, snapshot: ObjectId): Result<SnapshotResult> =
        history.restoreAll(workingCopy.path, snapshot)

    /** Always a new, labelled snapshot: the way back from this operation, kept until the user deletes it. */
    override suspend fun beforeRisky(workingCopy: WorkingCopy, operation: RiskyOperation): Result<Unit> =
        when (val r = history.snapshot(workingCopy.path, "before ${operation.label}")) {
            is Result.Success -> Result.Success(Unit)
            is Result.Failure -> r
        }
}
