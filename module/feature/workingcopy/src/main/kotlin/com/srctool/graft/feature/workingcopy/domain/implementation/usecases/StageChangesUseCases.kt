// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain.implementation.usecases

import com.srctool.graft.data.git.api.models.ChangeSelection
import com.srctool.graft.data.git.api.models.DiffLine
import com.srctool.graft.data.git.api.models.DiffOptions
import com.srctool.graft.data.git.api.models.DiffTarget
import com.srctool.graft.data.git.api.repositories.DiffRepository
import com.srctool.graft.data.git.api.repositories.LocalHistoryRepository
import com.srctool.graft.data.git.api.repositories.StagingRepository
import com.srctool.graft.feature.workingcopy.domain.api.models.ChangeRow
import com.srctool.graft.feature.workingcopy.domain.api.models.DiffSide
import com.srctool.graft.feature.workingcopy.domain.api.models.DiscardRecord
import com.srctool.graft.feature.workingcopy.domain.api.models.FileInfo
import com.srctool.graft.feature.workingcopy.domain.api.models.HunkInfo
import com.srctool.graft.feature.workingcopy.domain.api.models.UndoDiscardOutcome
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingCopy
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingDiff
import com.srctool.graft.feature.workingcopy.domain.api.usecases.StageChangesUseCase
import com.srctool.graft.feature.workingcopy.domain.api.usecases.WorkingDiffUseCase
import com.srctool.sinew.models.Result
import java.nio.file.Files
import java.nio.file.Path

internal class GitStageChangesUseCase(
    private val staging: StagingRepository,
    private val history: LocalHistoryRepository,
) : StageChangesUseCase {
    override suspend fun stage(workingCopy: WorkingCopy, selection: ChangeSelection): Result<Unit> =
        staging.stage(workingCopy.path, selection)

    override suspend fun unstage(workingCopy: WorkingCopy, selection: ChangeSelection): Result<Unit> =
        staging.unstage(workingCopy.path, selection)

    override suspend fun discard(workingCopy: WorkingCopy, selection: ChangeSelection): Result<DiscardRecord> {
        when (val r = staging.discard(workingCopy.path, selection)) {
            is Result.Success -> Unit
            is Result.Failure -> return r
        }
        // The discard took (or found) the snapshot of the tree as it was; it is the newest one.
        val snapshot =
            when (val r = history.snapshots(workingCopy.path, limit = 1)) {
                is Result.Success -> r.data.firstOrNull()?.id
                is Result.Failure -> return r
            }
        val left = selection.files.associate { it.path to fingerprint(workingCopy.path.resolve(it.path)) }
        return Result.Success(DiscardRecord(snapshot, left))
    }

    override suspend fun undoDiscard(workingCopy: WorkingCopy, record: DiscardRecord): Result<UndoDiscardOutcome> {
        val snapshot = record.snapshot ?: return Result.Success(UndoDiscardOutcome.Unavailable)
        record.left.entries.firstOrNull { (path, print) -> fingerprint(workingCopy.path.resolve(path)) != print }?.let {
            return Result.Success(UndoDiscardOutcome.ChangedSince(it.key))
        }
        for (path in record.left.keys) {
            (history.restoreFile(workingCopy.path, snapshot, path) as? Result.Failure)?.let { return it }
        }
        return Result.Success(UndoDiscardOutcome.Restored)
    }
}

internal class GitWorkingDiffUseCase(private val diffs: DiffRepository) : WorkingDiffUseCase {
    override suspend fun read(
        workingCopy: WorkingCopy,
        row: ChangeRow,
        side: DiffSide,
        anyway: Boolean,
        limit: Long,
    ): Result<WorkingDiff> {
        row.submodule?.let { return Result.Success(WorkingDiff.Submodule(null, null)) }
        if (!anyway) {
            val size = sizeOnDisk(workingCopy.path.resolve(row.path))
            if (size != null && size > limit) return Result.Success(WorkingDiff.TooLarge(size))
        }
        val target = if (side == DiffSide.STAGED) DiffTarget.Staged else DiffTarget.Unstaged
        val paths = listOfNotNull(row.path, row.originalPath)
        val all =
            when (val r = diffs.diff(workingCopy.path, target, DiffOptions(), paths)) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        val file = all.firstOrNull { it.file.path == row.path } ?: return Result.Success(WorkingDiff.Nothing)
        if (file.file.isBinary) {
            return Result.Success(
                WorkingDiff.Binary(sizeOnDisk(workingCopy.path.resolve(row.path))),
            )
        }
        if (file.hunks.isEmpty()) {
            if (file.file.modeChanged) {
                return Result.Success(WorkingDiff.ModeOnly(file.file.oldMode, file.file.newMode))
            }
            file.file.oldPath?.let { return Result.Success(WorkingDiff.Renamed(it)) }
            return Result.Success(WorkingDiff.Nothing)
        }
        val info =
            FileInfo(
                row.path,
                file.hunks.mapIndexed { index, hunk ->
                    HunkInfo(
                        index,
                        hunk.lines.indices.filterTo(HashSet()) {
                            hunk.lines[it].kind !=
                                DiffLine.Kind.CONTEXT
                        },
                    )
                },
                file.file.oldPath,
            )
        return Result.Success(WorkingDiff.Text(file, info))
    }

    private fun sizeOnDisk(file: Path): Long? = try {
        if (Files.isRegularFile(file)) Files.size(file) else null
    } catch (_: java.io.IOException) {
        null
    }
}
