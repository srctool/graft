// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.presentation.history

import com.srctool.graft.data.git.api.models.ChangedFile
import com.srctool.graft.data.git.api.models.DeletedFile
import com.srctool.graft.data.git.api.models.DiffLine
import com.srctool.graft.data.git.api.models.FileDiff
import com.srctool.graft.data.git.api.models.FileStatus
import com.srctool.graft.data.git.api.models.FileVersion
import com.srctool.graft.data.git.api.models.Hunk
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.Snapshot
import com.srctool.graft.data.git.api.models.SnapshotHit
import com.srctool.graft.data.git.api.models.SnapshotResult
import com.srctool.graft.feature.workingcopy.domain.api.models.HistoryDiff
import com.srctool.graft.feature.workingcopy.domain.api.models.HistorySearch
import com.srctool.graft.feature.workingcopy.domain.api.models.RestoreOutcome
import com.srctool.graft.feature.workingcopy.domain.api.models.RestoreRecord
import com.srctool.graft.feature.workingcopy.domain.api.models.UndoRestoreOutcome
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingCopy
import com.srctool.graft.feature.workingcopy.domain.api.usecases.LocalHistoryUseCase
import com.srctool.sinew.models.Result
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** A history of three snapshots over two days, with one changed, one deleted and one added file in the newest. */
internal object HistorySample {
    val today: LocalDate = LocalDate.of(2026, 11, 26)

    private fun at(day: LocalDate, hour: Int, minute: Int) =
        LocalDateTime.of(day.year, day.month, day.dayOfMonth, hour, minute).toEpochSecond(ZoneOffset.UTC)

    private fun snapshot(n: Int, time: Long, label: String?) =
        Snapshot(ObjectId(n.toString().repeat(40)), null, time, label, ObjectId("e".repeat(40)), label.orEmpty())

    val newest = snapshot(3, at(today, 14, 2), "before discard")
    val middle = snapshot(2, at(today, 13, 57), null)
    val oldest = snapshot(1, at(today.minusDays(1), 18, 11), "before refactor")
    val snapshots = listOf(newest, middle, oldest)

    private fun file(path: String, status: FileStatus) = ChangedFile(status, path, null, null, "100644", "100644", 2, 1)

    val login = file("src/login/Login.kt", FileStatus.MODIFIED)
    val notes = file("notes.txt", FileStatus.DELETED)
    val backoff = file("src/login/Backoff.kt", FileStatus.ADDED)
    val files = listOf(login, notes, backoff)

    fun diff(path: String = login.path): HistoryDiff.Text {
        val lines =
            listOf(
                DiffLine(DiffLine.Kind.CONTEXT, 10, 10, "val t = 30"),
                DiffLine(DiffLine.Kind.REMOVED, 11, null, "val timeout = 30"),
                DiffLine(DiffLine.Kind.ADDED, null, 11, "val timeout = Duration.ofSeconds(30)"),
                DiffLine(DiffLine.Kind.CONTEXT, 12, 12, "return connect(timeout)"),
            )
        val changed = ChangedFile(FileStatus.MODIFIED, path, null, null, "100644", "100644", 1, 1)
        return HistoryDiff.Text(FileDiff(changed, listOf(Hunk(10, 3, 10, 3, "fun login()", lines))))
    }
}

/** Records what the screen asked for; [failure] makes the next read or write fail. */
internal class FakeLocalHistory : LocalHistoryUseCase {
    var snapshots = HistorySample.snapshots
    var changed = HistorySample.files
    var versions =
        listOf(
            FileVersion(HistorySample.newest, null),
            FileVersion(HistorySample.middle, ObjectId("f".repeat(40))),
        )
    var deleted = listOf(DeletedFile("notes.txt", HistorySample.middle))
    var hits = listOf(SnapshotHit(HistorySample.middle, listOf("src/login/Login.kt")))
    var diff: HistoryDiff = HistorySample.diff()
    var failure: Result.Failure? = null
    var outcome: RestoreOutcome? = null
    var undoOutcome: UndoRestoreOutcome = UndoRestoreOutcome.Restored
    var created = true
    val calls = ArrayList<String>()

    private val record = RestoreRecord.Files(ObjectId("9".repeat(40)), mapOf("src/login/Login.kt" to "x"))

    private fun <T> answer(value: T): Result<T> = failure ?: Result.Success(value)

    private fun short(id: ObjectId) = id.hex.take(1)

    override suspend fun timeline(workingCopy: WorkingCopy) = answer(snapshots)

    override suspend fun changes(workingCopy: WorkingCopy, snapshot: ObjectId) = answer(changed)

    override suspend fun versions(workingCopy: WorkingCopy, path: String) = answer(versions)

    override suspend fun search(workingCopy: WorkingCopy, query: String, kind: HistorySearch) =
        answer(hits).also { calls += "search $kind $query" }

    override suspend fun deleted(workingCopy: WorkingCopy) = answer(deleted)

    override suspend fun diff(
        workingCopy: WorkingCopy,
        snapshot: ObjectId,
        path: String,
        anyway: Boolean,
        limit: Long,
    ) = answer(diff).also { calls += "diff ${short(snapshot)} $path${if (anyway) " anyway" else ""}" }

    override suspend fun snapshotNow(workingCopy: WorkingCopy, label: String?) =
        answer(SnapshotResult(HistorySample.newest, created)).also { calls += "snapshot $label" }

    private fun restored(call: String): Result<RestoreOutcome> =
        answer(outcome ?: RestoreOutcome.Done(record)).also { calls += call }

    override suspend fun restoreFile(workingCopy: WorkingCopy, snapshot: ObjectId, path: String) =
        restored("restoreFile ${short(snapshot)} $path")

    override suspend fun restoreHunks(workingCopy: WorkingCopy, snapshot: ObjectId, path: String, hunks: Set<Int>) =
        restored("restoreHunks ${short(snapshot)} $path ${hunks.sorted()}")

    override suspend fun restoreAll(workingCopy: WorkingCopy, snapshot: ObjectId): Result<RestoreOutcome> {
        calls += "restoreAll ${short(snapshot)}"
        val before = ObjectId("9".repeat(40))
        return answer(RestoreOutcome.Done(RestoreRecord.Everything(before, before, 2)))
    }

    override suspend fun undo(workingCopy: WorkingCopy, record: RestoreRecord) =
        answer(undoOutcome).also { calls += "undo" }

    override suspend fun purge(workingCopy: WorkingCopy, path: String) = answer(3).also { calls += "purge $path" }
}
