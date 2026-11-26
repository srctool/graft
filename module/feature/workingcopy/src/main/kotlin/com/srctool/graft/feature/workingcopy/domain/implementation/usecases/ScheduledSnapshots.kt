// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain.implementation.usecases

import com.srctool.graft.data.git.api.repositories.LocalHistoryRepository
import com.srctool.graft.presentation.session.AutoSnapshots
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.nio.file.Path

/** [now] is milliseconds from any fixed start, so a test can drive the schedule with virtual time. */
internal class ScheduledSnapshots(
    private val history: LocalHistoryRepository,
    private val now: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) : AutoSnapshots {
    override suspend fun run(repository: Path, changes: Flow<Unit>) {
        coroutineScope {
            val scheduler = SnapshotScheduler(this, now, snapshot = { history.snapshot(it) })
            // Listening starts first, so nothing changed while the first snapshot is taken is missed.
            launch { changes.collect { scheduler.changed(repository) } }
            history.thin(repository)
            history.snapshot(repository)
            awaitCancellation()
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
