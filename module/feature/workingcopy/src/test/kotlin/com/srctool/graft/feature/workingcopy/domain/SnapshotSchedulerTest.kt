// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain

import com.srctool.graft.feature.workingcopy.domain.api.usecases.SnapshotSchedule
import com.srctool.graft.feature.workingcopy.domain.implementation.usecases.SnapshotScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When the quiet-time snapshot is due: the pure rule, then the scheduler on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotSchedulerTest {
    private val quiet = 3_000L
    private val interval = 300_000L

    // ---- the rule

    @Test
    fun `nothing changed, nothing due`() {
        assertNull(SnapshotSchedule(quiet, interval).dueAt())
    }

    @Test
    fun `a change is snapshotted once files have been quiet for a moment`() {
        val schedule = SnapshotSchedule(quiet, interval)
        schedule.onChange(1_000)
        assertEquals(4_000L, schedule.dueAt())
        schedule.onChange(3_500)
        assertEquals(6_500L, schedule.dueAt())
    }

    @Test
    fun `changes that never stop are snapshotted at the interval, not starved`() {
        val schedule = SnapshotSchedule(quiet, interval)
        var t = 0L
        while (t <= 400_000) {
            schedule.onChange(t)
            t += 1_000
        }
        assertEquals(300_000L, schedule.dueAt())
    }

    @Test
    fun `a snapshot is never closer than the interval to the last one`() {
        val schedule = SnapshotSchedule(quiet, interval)
        schedule.onChange(0)
        schedule.onSnapshot(3_000)
        assertNull(schedule.dueAt())
        schedule.onChange(10_000)
        assertEquals(303_000L, schedule.dueAt())
    }

    @Test
    fun `after the interval the next quiet moment is the time`() {
        val schedule = SnapshotSchedule(quiet, interval)
        schedule.onChange(0)
        schedule.onSnapshot(3_000)
        schedule.onChange(400_000)
        assertEquals(403_000L, schedule.dueAt())
    }

    // ---- the scheduler

    private val repo: Path = Paths.get("/work/repo")
    private val other: Path = Paths.get("/work/other")

    private class Rig(scope: TestScope, snapshotTakes: Long = 0) {
        val taken = ArrayList<Pair<Path, Long>>()
        val scheduler =
            SnapshotScheduler(
                scope.backgroundScope,
                now = { scope.currentTime },
                snapshot = { repository ->
                    taken += repository to scope.currentTime
                    if (snapshotTakes > 0) delay(snapshotTakes)
                },
                quietMillis = 3_000,
                intervalMillis = 300_000,
            )
    }

    @Test
    fun `a burst of changes becomes one snapshot after the quiet moment`() = runTest {
        val rig = Rig(this)
        rig.scheduler.changed(repo)
        advanceTimeBy(1_000)
        rig.scheduler.changed(repo)
        advanceTimeBy(1_000)
        rig.scheduler.changed(repo)
        advanceTimeBy(2_999)
        assertTrue(rig.taken.isEmpty())
        advanceTimeBy(2)
        assertEquals(listOf(repo to 5_000L), rig.taken)
        advanceTimeBy(1_000_000)
        assertEquals(1, rig.taken.size, "nothing changed since")
    }

    @Test
    fun `changes after a snapshot wait for the interval`() = runTest {
        val rig = Rig(this)
        rig.scheduler.changed(repo)
        advanceTimeBy(3_001)
        advanceTimeBy(7_000)
        rig.scheduler.changed(repo)
        advanceTimeBy(100_000)
        assertEquals(1, rig.taken.size)
        advanceTimeBy(200_000)
        assertEquals(listOf(3_000L, 303_000L), rig.taken.map { it.second })
    }

    @Test
    fun `constant changes are snapshotted every five minutes`() = runTest {
        val rig = Rig(this)
        repeat(700) {
            rig.scheduler.changed(repo)
            advanceTimeBy(1_000)
        }
        // The first at the cap from the first change; the next from the first change after that snapshot (1 s later).
        assertEquals(listOf(300_000L, 601_000L), rig.taken.map { it.second })
    }

    @Test
    fun `each repository has its own schedule`() = runTest {
        val rig = Rig(this)
        rig.scheduler.changed(repo)
        advanceTimeBy(2_000)
        rig.scheduler.changed(other)
        advanceTimeBy(1_001)
        assertEquals(listOf(repo), rig.taken.map { it.first })
        advanceTimeBy(2_000)
        assertEquals(listOf(repo, other), rig.taken.map { it.first })
    }

    @Test
    fun `a change during a snapshot gets the next one, not a second at once`() = runTest {
        val rig = Rig(this, snapshotTakes = 5_000)
        rig.scheduler.changed(repo)
        advanceTimeBy(3_001)
        assertEquals(1, rig.taken.size)
        advanceTimeBy(1_000)
        rig.scheduler.changed(repo)
        advanceTimeBy(60_000)
        assertEquals(1, rig.taken.size)
        advanceTimeBy(250_000)
        assertEquals(listOf(3_000L, 303_000L), rig.taken.map { it.second })
    }

    @Test
    fun `a repository that is closed is not snapshotted`() = runTest {
        val rig = Rig(this)
        rig.scheduler.changed(repo)
        advanceTimeBy(1_000)
        rig.scheduler.forget(repo)
        advanceTimeBy(100_000)
        assertTrue(rig.taken.isEmpty())
        rig.scheduler.changed(repo)
        advanceTimeBy(3_001)
        assertEquals(1, rig.taken.size)
    }

    @Test
    fun `a snapshot that throws doesn't stop the schedule`() = runTest {
        val taken = ArrayList<Long>()
        val scheduler =
            SnapshotScheduler(
                backgroundScope,
                now = { currentTime },
                snapshot = {
                    taken += currentTime
                    error("disk full")
                },
                quietMillis = 3_000,
                intervalMillis = 300_000,
            )
        scheduler.changed(repo)
        advanceTimeBy(3_001)
        scheduler.changed(repo)
        advanceTimeBy(300_000)
        assertEquals(2, taken.size)
    }
}
