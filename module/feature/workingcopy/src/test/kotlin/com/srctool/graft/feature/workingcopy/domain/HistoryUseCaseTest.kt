// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain

import com.srctool.graft.data.git.api.models.ChangedFile
import com.srctool.graft.data.git.api.models.DiffOptions
import com.srctool.graft.data.git.api.models.DiffTarget
import com.srctool.graft.data.git.api.models.FileDiff
import com.srctool.graft.data.git.api.models.FileStatus
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.Snapshot
import com.srctool.graft.data.git.api.models.SnapshotResult
import com.srctool.graft.data.git.api.repositories.DiffRepository
import com.srctool.graft.data.git.api.repositories.LocalHistoryRepository
import com.srctool.graft.feature.workingcopy.domain.api.models.HistoryDiff
import com.srctool.graft.feature.workingcopy.domain.api.models.RestoreOutcome
import com.srctool.graft.feature.workingcopy.domain.api.models.RestoreRecord
import com.srctool.graft.feature.workingcopy.domain.api.models.UndoRestoreOutcome
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingCopy
import com.srctool.graft.feature.workingcopy.domain.implementation.usecases.GitHistoryUseCase
import com.srctool.graft.feature.workingcopy.domain.implementation.usecases.SnapshotUseCase
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val TARGET = ObjectId("a".repeat(40))
private val BEFORE = ObjectId("b".repeat(40))

private fun diffOf(path: String, status: FileStatus) =
    FileDiff(ChangedFile(status, path, null, null, "100644", "100644", 1, 0), emptyList())

private class RestoreHistory : LocalHistoryRepository by unsupported() {
    val calls = ArrayList<String>()
    var tree = ObjectId("1".repeat(40))

    /** What [diffToNow] answers, per snapshot: null means the file is as that snapshot has it. */
    var held: FileDiff? = null
    var sinceBefore: FileDiff? = null

    override suspend fun snapshot(repository: Path, label: String?): Result<SnapshotResult> {
        calls += "snapshot ${label.orEmpty()}".trim()
        return Result.Success(SnapshotResult(Snapshot(BEFORE, null, 0, label, tree, ""), true))
    }

    override suspend fun diffToNow(
        repository: Path,
        snapshot: ObjectId,
        path: String,
        contextLines: Int,
    ): Result<FileDiff?> = Result.Success(if (calls.any { it.startsWith("restore") }) sinceBefore else held)

    override suspend fun restoreFile(repository: Path, snapshot: ObjectId, path: String): Result<Unit> {
        calls += "restore ${snapshot.hex.take(1)} $path"
        return Result.Success(Unit)
    }

    override suspend fun restoreAll(repository: Path, snapshot: ObjectId): Result<SnapshotResult> {
        calls += "restoreAll ${snapshot.hex.take(1)}"
        return Result.Success(SnapshotResult(Snapshot(BEFORE, null, 0, "before restore", tree, ""), true))
    }
}

private class RemovedFiles : DiffRepository by unsupported() {
    override suspend fun files(repository: Path, target: DiffTarget, options: DiffOptions): Result<List<ChangedFile>> =
        Result.Success(listOf(diffOf("new.txt", FileStatus.DELETED).file, diffOf("f", FileStatus.MODIFIED).file))
}

class HistoryUseCaseTest {
    private val root = createTempDirectory("graft-history")
    private val copy = WorkingCopy(root, "repo")
    private val history = RestoreHistory()
    private val useCase = GitHistoryUseCase(history, RemovedFiles(), SnapshotUseCase(history))

    private fun done(outcome: RestoreOutcome) = assertIs<RestoreOutcome.Done>(outcome).record

    @Test
    fun `a restore takes its snapshot first`() = runTest {
        useCase.restoreFile(copy, TARGET, "doc.txt").getOrThrow()
        assertEquals(listOf("snapshot before restore", "restore a doc.txt"), history.calls)
    }

    @Test
    fun `a file the snapshot can't hold is not written over`() = runTest {
        history.held = diffOf("big.bin", FileStatus.ADDED)
        val outcome = useCase.restoreFile(copy, TARGET, "big.bin").getOrThrow()
        assertEquals(RestoreOutcome.Unprotected("big.bin"), outcome)
        assertEquals(listOf("snapshot before restore"), history.calls)
    }

    @Test
    fun `undo leaves a file edited since the restore alone`() = runTest {
        root.resolve("doc.txt").writeText("restored")
        val record = done(useCase.restoreFile(copy, TARGET, "doc.txt").getOrThrow())
        root.resolve("doc.txt").writeText("typed since")
        assertEquals(UndoRestoreOutcome.ChangedSince("doc.txt"), useCase.undo(copy, record).getOrThrow())
        assertEquals(1, history.calls.count { it.startsWith("restore ") })
    }

    @Test
    fun `undo puts the earlier content back, or removes a file the restore made`() = runTest {
        root.resolve("doc.txt").writeText("restored")
        val record = done(useCase.restoreFile(copy, TARGET, "doc.txt").getOrThrow())
        history.sinceBefore = diffOf("doc.txt", FileStatus.MODIFIED)
        assertEquals(UndoRestoreOutcome.Restored, useCase.undo(copy, record).getOrThrow())
        assertEquals("restore b doc.txt", history.calls.last())

        history.sinceBefore = diffOf("doc.txt", FileStatus.ADDED)
        assertEquals(UndoRestoreOutcome.Restored, useCase.undo(copy, record).getOrThrow())
        assertFalse(root.resolve("doc.txt").exists())
    }

    @Test
    fun `restoring everything counts what it removed and undoes only an untouched tree`() = runTest {
        val record = assertIs<RestoreRecord.Everything>(done(useCase.restoreAll(copy, TARGET).getOrThrow()))
        assertEquals(1, record.removed)
        history.tree = ObjectId("2".repeat(40))
        assertEquals(UndoRestoreOutcome.ChangedSince(null), useCase.undo(copy, record).getOrThrow())
        history.tree = record.after
        assertEquals(UndoRestoreOutcome.Restored, useCase.undo(copy, record).getOrThrow())
        assertTrue("restoreAll b" in history.calls)
    }

    @Test
    fun `a large file's diff waits to be asked for`() = runTest {
        root.resolve("doc.txt").writeText("0123456789")
        assertEquals(HistoryDiff.TooLarge(10), useCase.diff(copy, TARGET, "doc.txt", limit = 4).getOrThrow())
        assertEquals(HistoryDiff.Same, useCase.diff(copy, TARGET, "doc.txt", anyway = true, limit = 4).getOrThrow())
    }
}
