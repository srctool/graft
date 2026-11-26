// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain.implementation.usecases

import com.srctool.graft.data.git.api.models.SnapshotResult
import com.srctool.graft.data.git.api.repositories.LocalHistoryRepository
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingCopy
import com.srctool.graft.feature.workingcopy.domain.api.usecases.BeforeRiskyOperation
import com.srctool.graft.feature.workingcopy.domain.api.usecases.RiskyOperation
import com.srctool.sinew.models.Result

/** Taking snapshots: by hand, and before an operation that could lose work. */
internal class SnapshotUseCase(private val history: LocalHistoryRepository) : BeforeRiskyOperation {
    /** A labelled snapshot is always made; an unlabelled one only when the tree changed since the last. */
    suspend fun snapshotNow(workingCopy: WorkingCopy, label: String? = null): Result<SnapshotResult> =
        history.snapshot(workingCopy.path, label?.trim()?.ifEmpty { null })

    /** Always a new, labelled snapshot: the way back from this operation, kept until the user deletes it. */
    override suspend fun beforeRisky(workingCopy: WorkingCopy, operation: RiskyOperation): Result<Unit> =
        when (val r = history.snapshot(workingCopy.path, "before ${operation.label}")) {
            is Result.Success -> Result.Success(Unit)
            is Result.Failure -> r
        }
}
