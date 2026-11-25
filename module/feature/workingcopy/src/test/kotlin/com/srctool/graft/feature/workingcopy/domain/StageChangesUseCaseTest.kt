// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain

import com.srctool.graft.data.git.api.models.ChangeSelection
import com.srctool.graft.data.git.api.models.ChangedFile
import com.srctool.graft.data.git.api.models.DiffLine
import com.srctool.graft.data.git.api.models.DiffOptions
import com.srctool.graft.data.git.api.models.DiffTarget
import com.srctool.graft.data.git.api.models.FileDiff
import com.srctool.graft.data.git.api.models.FileSelection
import com.srctool.graft.data.git.api.models.FileStatus
import com.srctool.graft.data.git.api.models.Hunk
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.Snapshot
import com.srctool.graft.data.git.api.repositories.DiffRepository
import com.srctool.graft.data.git.api.repositories.LocalHistoryRepository
import com.srctool.graft.data.git.api.repositories.StagingRepository
import com.srctool.graft.feature.workingcopy.domain.api.models.ChangeRow
import com.srctool.graft.feature.workingcopy.domain.api.models.DiffSide
import com.srctool.graft.feature.workingcopy.domain.api.models.RowStatus
import com.srctool.graft.feature.workingcopy.domain.api.models.UndoDiscardOutcome
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingCopy
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingDiff
import com.srctool.graft.feature.workingcopy.domain.implementation.usecases.GitStageChangesUseCase
import com.srctool.graft.feature.workingcopy.domain.implementation.usecases.GitWorkingDiffUseCase
import com.srctool.sinew.models.Result
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val SNAPSHOT = ObjectId("b".repeat(40))

private class FakeStagingRepository : StagingRepository by unsupported() {
    val calls = ArrayList<String>()
    var failure: Result.Failure? = null

    override suspend fun stage(repository: Path, selection: ChangeSelection, contextLines: Int): Result<Unit> =
        failure ?: Result.Success(Unit).also { calls += "stage" }

    override suspend fun unstage(repository: Path, selection: ChangeSelection, contextLines: Int): Result<Unit> =
        failure ?: Result.Success(Unit).also { calls += "unstage" }

    override suspend fun discard(repository: Path, selection: ChangeSelection, contextLines: Int): Result<Unit> =
        failure ?: Result.Success(Unit).also { calls += "discard" }
}

private class FakeHistory(var snapshots: List<Snapshot> = emptyList()) : LocalHistoryRepository by unsupported() {
    val restored = ArrayList<Pair<ObjectId, String>>()

    override suspend fun snapshots(repository: Path, limit: Int): Result<List<Snapshot>> =
        Result.Success(snapshots.take(limit))

    override suspend fun restoreFile(repository: Path, snapshot: ObjectId, path: String): Result<Unit> {
        restored += snapshot to path
        return Result.Success(Unit)
    }
}

class StageChangesUseCaseTest {
    private val root = createTempDirectory("graft-stage")
    private val copy = WorkingCopy(root, "repo")
    private val staging = FakeStagingRepository()
    private val history = FakeHistory(listOf(Snapshot(SNAPSHOT, null, 1, "before discard", SNAPSHOT, "m")))
    private val useCase = GitStageChangesUseCase(staging, history)
    private val selection = ChangeSelection(listOf(FileSelection("a.txt", FileSelection.Scope.WholeFile)))

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `stage and unstage go to the staging repository as they are`() = runTest {
        useCase.stage(copy, selection)
        useCase.unstage(copy, selection)
        assertEquals(listOf("stage", "unstage"), staging.calls)
    }

    @Test
    fun `a failed stage is the failure, untouched`() = runTest {
        staging.failure = failure("APLY")
        assertTrue(useCase.stage(copy, selection) is Result.Failure)
    }

    @Test
    fun `a discard hands back the snapshot it can be undone from and what the files look like now`() = runTest {
        root.resolve("a.txt").writeText("after")
        val record = (useCase.discard(copy, selection) as Result.Success).data
        assertEquals(SNAPSHOT, record.snapshot)
        assertEquals(setOf("a.txt"), record.left.keys)
        assertTrue(record.left.getValue("a.txt") != null)
    }

    @Test
    fun `a failed discard has no record, and nothing is read`() = runTest {
        staging.failure = failure("REST")
        assertTrue(useCase.discard(copy, selection) is Result.Failure)
        assertTrue(history.restored.isEmpty())
    }

    @Test
    fun `undo restores each discarded file from the snapshot when it is as the discard left it`() = runTest {
        root.resolve("a.txt").writeText("after")
        val record = (useCase.discard(copy, selection) as Result.Success).data
        val outcome = (useCase.undoDiscard(copy, record) as Result.Success).data
        assertEquals(UndoDiscardOutcome.Restored, outcome)
        assertEquals(listOf(SNAPSHOT to "a.txt"), history.restored)
    }

    @Test
    fun `undo restores nothing when a file was edited after the discard`() = runTest {
        root.resolve("a.txt").writeText("after")
        val record = (useCase.discard(copy, selection) as Result.Success).data
        root.resolve("a.txt").writeText("edited since")
        val outcome = (useCase.undoDiscard(copy, record) as Result.Success).data
        assertEquals(UndoDiscardOutcome.ChangedSince("a.txt"), outcome)
        assertTrue(history.restored.isEmpty())
    }

    @Test
    fun `a file that was deleted by the discard and is still gone can be restored`() = runTest {
        val record = (useCase.discard(copy, selection) as Result.Success).data
        assertNull(record.left.getValue("a.txt"))
        assertEquals(UndoDiscardOutcome.Restored, (useCase.undoDiscard(copy, record) as Result.Success).data)
    }

    @Test
    fun `undo with no snapshot says there is nothing to restore from`() = runTest {
        history.snapshots = emptyList()
        val record = (useCase.discard(copy, selection) as Result.Success).data
        assertNull(record.snapshot)
        assertEquals(UndoDiscardOutcome.Unavailable, (useCase.undoDiscard(copy, record) as Result.Success).data)
    }
}

private class FakeDiffRepository(var result: List<FileDiff> = emptyList()) : DiffRepository by unsupported() {
    var target: DiffTarget? = null
    var paths: List<String> = emptyList()

    override suspend fun diff(
        repository: Path,
        target: DiffTarget,
        options: DiffOptions,
        paths: List<String>,
    ): Result<List<FileDiff>> {
        this.target = target
        this.paths = paths
        return Result.Success(result)
    }
}

class WorkingDiffUseCaseTest {
    private val root = createTempDirectory("graft-wdiff")
    private val copy = WorkingCopy(root, "repo")
    private val diffs = FakeDiffRepository()
    private val useCase = GitWorkingDiffUseCase(diffs)
    private val row = ChangeRow("a.txt", RowStatus.MODIFIED)

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun file(
        path: String = "a.txt",
        additions: Int? = 1,
        oldMode: String = "100644",
        newMode: String = "100644",
        oldPath: String? = null,
        hunks: List<Hunk> = emptyList(),
    ) = FileDiff(ChangedFile(FileStatus.MODIFIED, path, oldPath, null, oldMode, newMode, additions, 0), hunks)

    private val hunk =
        Hunk(
            1,
            3,
            1,
            3,
            "",
            listOf(
                DiffLine(DiffLine.Kind.CONTEXT, 1, 1, "a"),
                DiffLine(DiffLine.Kind.REMOVED, 2, null, "b"),
                DiffLine(DiffLine.Kind.ADDED, null, 2, "B"),
                DiffLine(DiffLine.Kind.CONTEXT, 3, 3, "c"),
            ),
        )

    @Test
    fun `each side reads its own target and only the file's paths`() = runTest {
        diffs.result = listOf(file(hunks = listOf(hunk)))
        useCase.read(copy, row, DiffSide.STAGED)
        assertEquals(DiffTarget.Staged, diffs.target)
        useCase.read(copy, ChangeRow("b.txt", RowStatus.RENAMED, originalPath = "a.txt"), DiffSide.UNSTAGED)
        assertEquals(DiffTarget.Unstaged, diffs.target)
        assertEquals(listOf("b.txt", "a.txt"), diffs.paths)
    }

    @Test
    fun `a text diff carries the positions of its changed lines, context counted`() = runTest {
        diffs.result = listOf(file(hunks = listOf(hunk)))
        val text = assertIs<WorkingDiff.Text>((useCase.read(copy, row, DiffSide.UNSTAGED) as Result.Success).data)
        assertEquals(setOf(1, 2), text.info.hunks.single().changeLines)
        assertEquals("a.txt", text.info.path)
    }

    @Test
    fun `binary, mode-only, rename-only and no change are told apart`() = runTest {
        diffs.result = listOf(file(additions = null))
        assertIs<WorkingDiff.Binary>((useCase.read(copy, row, DiffSide.UNSTAGED) as Result.Success).data)
        diffs.result = listOf(file(oldMode = "100644", newMode = "100755"))
        assertEquals(
            WorkingDiff.ModeOnly("100644", "100755"),
            (useCase.read(copy, row, DiffSide.UNSTAGED) as Result.Success).data,
        )
        diffs.result = listOf(file(oldPath = "old.txt"))
        assertEquals(
            WorkingDiff.Renamed("old.txt"),
            (useCase.read(copy, row, DiffSide.UNSTAGED) as Result.Success).data,
        )
        diffs.result = emptyList()
        assertEquals(WorkingDiff.Nothing, (useCase.read(copy, row, DiffSide.UNSTAGED) as Result.Success).data)
    }

    @Test
    fun `a file over the limit is not read unless asked`() = runTest {
        Files.write(root.resolve("a.txt"), ByteArray(100))
        diffs.result = listOf(file(hunks = listOf(hunk)))
        val big = (useCase.read(copy, row, DiffSide.UNSTAGED, limit = 50) as Result.Success).data
        assertEquals(WorkingDiff.TooLarge(100), big)
        assertNull(diffs.target, "git wasn't asked")
        assertIs<WorkingDiff.Text>(
            (useCase.read(copy, row, DiffSide.UNSTAGED, anyway = true, limit = 50) as Result.Success).data,
        )
    }

    @Test
    fun `a submodule row is a submodule, never a diff`() = runTest {
        val sub =
            ChangeRow(
                "libs/engine",
                RowStatus.MODIFIED,
                submodule = com.srctool.graft.data.git.api.models.SubmoduleChange(true, false, false),
            )
        assertIs<WorkingDiff.Submodule>((useCase.read(copy, sub, DiffSide.UNSTAGED) as Result.Success).data)
        assertNull(diffs.target)
    }
}
