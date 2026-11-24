// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain.api.usecases

import java.nio.file.Path

/**
 * What the session tells the quiet-time snapshot scheduler: files changed in [repository]. The scheduler asks for a
 * snapshot when they have stopped changing, and at most every five minutes while they keep changing (Local History,
 * "When a snapshot is taken").
 */
internal interface SnapshotWatcher {
    fun changed(repository: Path)

    /** The repository was closed: a pending snapshot is dropped. */
    fun forget(repository: Path)
}

/**
 * When a snapshot is due, as a pure rule over times in milliseconds. A snapshot waits for [quietMillis] with no change,
 * is never closer than [intervalMillis] to the last one, and is never put off by constant changes beyond
 * [intervalMillis] after the first unsnapshotted change.
 */
internal class SnapshotSchedule(
    private val quietMillis: Long = QUIET_MILLIS,
    private val intervalMillis: Long = INTERVAL_MILLIS,
) {
    private var firstPending: Long? = null
    private var lastChange: Long = 0
    private var lastSnapshot: Long? = null

    fun onChange(now: Long) {
        if (firstPending == null) firstPending = now
        lastChange = now
    }

    /** When to snapshot, or null when nothing has changed since the last one. */
    fun dueAt(): Long? {
        val first = firstPending ?: return null
        val wanted = minOf(lastChange + quietMillis, first + intervalMillis)
        return maxOf(wanted, lastSnapshot?.plus(intervalMillis) ?: Long.MIN_VALUE)
    }

    /** A snapshot is being taken at [now]: what changed so far is covered, and a later change starts over. */
    fun onSnapshot(now: Long) {
        firstPending = null
        lastSnapshot = now
    }

    companion object {
        const val QUIET_MILLIS: Long = 3_000
        const val INTERVAL_MILLIS: Long = 5 * 60_000
    }
}
