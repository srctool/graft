// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain

import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.Snapshot
import com.srctool.graft.data.git.api.models.SnapshotResult
import com.srctool.graft.data.git.api.models.Thinning
import com.srctool.graft.data.git.api.repositories.LocalHistoryRepository
import com.srctool.graft.feature.workingcopy.domain.implementation.usecases.ScheduledSnapshots
import com.srctool.sinew.models.Result
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

private class CountingHistory : LocalHistoryRepository by unsupported() {
    val calls = ArrayList<String>()

    override suspend fun thin(repository: Path): Result<Thinning> =
        Result.Success(Thinning(0, 0, 0)).also { calls += "thin" }

    override suspend fun snapshot(repository: Path, label: String?): Result<SnapshotResult> {
        calls += "snapshot"
        val id = ObjectId("1".repeat(40))
        return Result.Success(SnapshotResult(Snapshot(id, null, 0, label, id, ""), true))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledSnapshotsTest {
    @Test
    fun `opening thins and snapshots, and a change is snapshotted once it settles`() = runTest {
        val history = CountingHistory()
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val running = launch { ScheduledSnapshots(history) { testScheduler.currentTime }.run(REPO, changes) }
        runCurrent()
        assertEquals(listOf("thin", "snapshot"), history.calls)

        changes.emit(Unit)
        advanceTimeBy(1_000)
        assertEquals(2, history.calls.size, "still changing: not yet")
        advanceTimeBy(10 * 60_000)
        assertEquals(listOf("thin", "snapshot", "snapshot"), history.calls)

        running.cancelAndJoin()
        changes.emit(Unit)
        advanceTimeBy(10 * 60_000)
        assertEquals(3, history.calls.size, "stopped with the repository")
    }
}
