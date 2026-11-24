// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain.implementation.usecases

import com.srctool.graft.feature.workingcopy.domain.api.usecases.SnapshotSchedule
import com.srctool.graft.feature.workingcopy.domain.api.usecases.SnapshotWatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * Runs [SnapshotSchedule] for each repository the session reports changes in. [snapshot] takes the snapshot and handles
 * its own failure (a failed automatic snapshot is not an error to show); [now] is milliseconds from any fixed start, so
 * a test can drive it with virtual time. A change that arrives while a snapshot is being taken waits for the next one.
 */
internal class SnapshotScheduler(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val snapshot: suspend (Path) -> Unit,
    private val quietMillis: Long = SnapshotSchedule.QUIET_MILLIS,
    private val intervalMillis: Long = SnapshotSchedule.INTERVAL_MILLIS,
) : SnapshotWatcher {
    private class Entry(val schedule: SnapshotSchedule) {
        var generation = 0L
        var running = false
    }

    private val entries = HashMap<Path, Entry>()

    override fun changed(repository: Path) {
        synchronized(entries) {
            val entry = entries.getOrPut(repository) { Entry(SnapshotSchedule(quietMillis, intervalMillis)) }
            entry.schedule.onChange(now())
            if (!entry.running) arm(repository, entry)
        }
    }

    override fun forget(repository: Path) {
        synchronized(entries) { entries.remove(repository)?.let { it.generation++ } }
    }

    /** Waits until the schedule says so. A later [changed] arms again, and the generation stales this wait. */
    private fun arm(repository: Path, entry: Entry) {
        val due = entry.schedule.dueAt() ?: return
        val generation = ++entry.generation
        scope.launch {
            delay((due - now()).coerceAtLeast(0))
            val go =
                synchronized(entries) {
                    if (entry.generation != generation || entries[repository] !== entry) {
                        false
                    } else {
                        entry.running = true
                        entry.schedule.onSnapshot(now())
                        true
                    }
                }
            if (!go) return@launch
            try {
                snapshot(repository)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The snapshot function reports its own failures; this loop must outlive one.
            } finally {
                synchronized(entries) {
                    entry.running = false
                    if (entries[repository] === entry) arm(repository, entry)
                }
            }
        }
    }
}
