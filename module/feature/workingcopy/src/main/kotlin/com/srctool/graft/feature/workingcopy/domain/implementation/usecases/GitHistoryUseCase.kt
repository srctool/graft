// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain.implementation.usecases

import com.srctool.graft.data.git.api.models.ChangedFile
import com.srctool.graft.data.git.api.models.DeletedFile
import com.srctool.graft.data.git.api.models.DiffTarget
import com.srctool.graft.data.git.api.models.FileStatus
import com.srctool.graft.data.git.api.models.FileVersion
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.Snapshot
import com.srctool.graft.data.git.api.models.SnapshotHit
import com.srctool.graft.data.git.api.models.SnapshotResult
import com.srctool.graft.data.git.api.repositories.DiffRepository
import com.srctool.graft.data.git.api.repositories.LocalHistoryRepository
import com.srctool.graft.feature.workingcopy.domain.api.models.HistoryDiff
import com.srctool.graft.feature.workingcopy.domain.api.models.HistorySearch
import com.srctool.graft.feature.workingcopy.domain.api.models.RestoreOutcome
import com.srctool.graft.feature.workingcopy.domain.api.models.RestoreRecord
import com.srctool.graft.feature.workingcopy.domain.api.models.UndoRestoreOutcome
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingCopy
import com.srctool.graft.feature.workingcopy.domain.api.usecases.LocalHistoryUseCase
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.map
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal class GitHistoryUseCase(
    private val history: LocalHistoryRepository,
    private val diffs: DiffRepository,
    private val snapshots: SnapshotUseCase,
) : LocalHistoryUseCase {
    override suspend fun timeline(workingCopy: WorkingCopy): Result<List<Snapshot>> =
        history.snapshots(workingCopy.path)

    override suspend fun changes(workingCopy: WorkingCopy, snapshot: ObjectId): Result<List<ChangedFile>> =
        history.changes(workingCopy.path, snapshot)

    override suspend fun versions(workingCopy: WorkingCopy, path: String): Result<List<FileVersion>> =
        history.versions(workingCopy.path, path)

    override suspend fun search(
        workingCopy: WorkingCopy,
        query: String,
        kind: HistorySearch,
    ): Result<List<SnapshotHit>> = when (kind) {
        HistorySearch.PATHS -> history.searchPaths(workingCopy.path, query)
        HistorySearch.TEXT -> history.searchText(workingCopy.path, query)
    }

    override suspend fun deleted(workingCopy: WorkingCopy): Result<List<DeletedFile>> =
        history.deletedFiles(workingCopy.path)

    override suspend fun diff(
        workingCopy: WorkingCopy,
        snapshot: ObjectId,
        path: String,
        anyway: Boolean,
        limit: Long,
    ): Result<HistoryDiff> {
        val size = sizeOnDisk(workingCopy.path.resolve(path))
        if (!anyway && size != null && size > limit) return Result.Success(HistoryDiff.TooLarge(size))
        return history.diffToNow(workingCopy.path, snapshot, path).map { diff ->
            when {
                diff == null -> HistoryDiff.Same
                diff.file.isBinary -> HistoryDiff.Binary(size)
                diff.hunks.isEmpty() && diff.file.status == FileStatus.MODIFIED -> HistoryDiff.Same
                else -> HistoryDiff.Text(diff)
            }
        }
    }

    override suspend fun snapshotNow(workingCopy: WorkingCopy, label: String?): Result<SnapshotResult> =
        snapshots.snapshotNow(workingCopy, label)

    override suspend fun restoreFile(
        workingCopy: WorkingCopy,
        snapshot: ObjectId,
        path: String,
    ): Result<RestoreOutcome> = restoring(workingCopy, path) {
        history.restoreFile(workingCopy.path, snapshot, path)
    }

    override suspend fun restoreHunks(
        workingCopy: WorkingCopy,
        snapshot: ObjectId,
        path: String,
        hunks: Set<Int>,
    ): Result<RestoreOutcome> = restoring(workingCopy, path) {
        history.restoreHunks(workingCopy.path, snapshot, path, hunks)
    }

    /** Snapshot, check the snapshot holds [path] as it is, then [restore]. */
    private suspend fun restoring(
        workingCopy: WorkingCopy,
        path: String,
        restore: suspend () -> Result<Unit>,
    ): Result<RestoreOutcome> {
        val before =
            when (val r = history.snapshot(workingCopy.path, BEFORE_RESTORE)) {
                is Result.Success -> r.data.snapshot.id
                is Result.Failure -> return r
            }
        when (val r = history.diffToNow(workingCopy.path, before, path)) {
            is Result.Success -> if (r.data != null) return Result.Success(RestoreOutcome.Unprotected(path))
            is Result.Failure -> return r
        }
        return restore().map {
            val left = mapOf(path to fingerprint(workingCopy.path.resolve(path)))
            RestoreOutcome.Done(RestoreRecord.Files(before, left))
        }
    }

    override suspend fun restoreAll(workingCopy: WorkingCopy, snapshot: ObjectId): Result<RestoreOutcome> {
        val before =
            when (val r = history.restoreAll(workingCopy.path, snapshot)) {
                is Result.Success -> r.data.snapshot.id
                is Result.Failure -> return r
            }
        val after =
            when (val r = history.snapshot(workingCopy.path)) {
                is Result.Success -> r.data.snapshot.tree
                is Result.Failure -> return r
            }
        return diffs.files(workingCopy.path, DiffTarget.Between(before.hex, snapshot.hex)).map { files ->
            val removed = files.count { it.status == FileStatus.DELETED }
            RestoreOutcome.Done(RestoreRecord.Everything(before, after, removed))
        }
    }

    override suspend fun undo(workingCopy: WorkingCopy, record: RestoreRecord): Result<UndoRestoreOutcome> =
        when (record) {
            is RestoreRecord.Files -> undoFiles(workingCopy, record)
            is RestoreRecord.Everything -> undoEverything(workingCopy, record)
        }

    private suspend fun undoFiles(workingCopy: WorkingCopy, record: RestoreRecord.Files): Result<UndoRestoreOutcome> {
        record.left.entries.firstOrNull { (path, print) -> fingerprint(workingCopy.path.resolve(path)) != print }?.let {
            return Result.Success(UndoRestoreOutcome.ChangedSince(it.key))
        }
        for (path in record.left.keys) {
            val diff =
                when (val r = history.diffToNow(workingCopy.path, record.before, path)) {
                    is Result.Success -> r.data ?: continue
                    is Result.Failure -> return r
                }
            if (diff.file.status == FileStatus.ADDED) {
                // The file didn't exist before the restore, so undoing the restore removes it again.
                try {
                    Files.deleteIfExists(workingCopy.path.resolve(path))
                } catch (_: IOException) {
                    return Result.Success(UndoRestoreOutcome.ChangedSince(path))
                }
            } else {
                (history.restoreFile(workingCopy.path, record.before, path) as? Result.Failure)?.let { return it }
            }
        }
        return Result.Success(UndoRestoreOutcome.Restored)
    }

    private suspend fun undoEverything(
        workingCopy: WorkingCopy,
        record: RestoreRecord.Everything,
    ): Result<UndoRestoreOutcome> {
        val now =
            when (val r = history.snapshot(workingCopy.path)) {
                is Result.Success -> r.data.snapshot.tree
                is Result.Failure -> return r
            }
        if (now != record.after) return Result.Success(UndoRestoreOutcome.ChangedSince(null))
        return history.restoreAll(workingCopy.path, record.before).map { UndoRestoreOutcome.Restored }
    }

    override suspend fun purge(workingCopy: WorkingCopy, path: String): Result<Int> =
        history.purge(workingCopy.path, path)

    private fun sizeOnDisk(file: Path): Long? = try {
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) Files.size(file) else null
    } catch (_: IOException) {
        null
    }

    private companion object {
        const val BEFORE_RESTORE = "before restore"
    }
}
