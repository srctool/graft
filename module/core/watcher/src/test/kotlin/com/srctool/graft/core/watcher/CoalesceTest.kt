// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.watcher

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Platform Integration 3.1: raw events become a few refreshes. On virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoalesceTest {
    private val status = RawChange.Path(ChangeKind.STATUS)
    private val refs = RawChange.Path(ChangeKind.REFS)
    private val operation = RawChange.Path(ChangeKind.OPERATION)

    @Test
    fun `a burst of status events is one refresh, 300 ms after the last`() = runTest {
        val raw = Channel<RawChange>(Channel.UNLIMITED)
        raw.consumeAsFlow().coalesce().test {
            repeat(10) {
                raw.send(status)
                advanceTimeBy(50.milliseconds)
            }
            // The last event was at 450 ms and it's now 500 ms: the refresh is due at 750 ms.
            advanceTimeBy(249.milliseconds)
            expectNoEvents()
            advanceTimeBy(2.milliseconds)
            assertEquals(RepoChange.StatusChanged, awaitItem())
            expectNoEvents()
            raw.close()
            awaitComplete()
        }
    }

    @Test
    fun `refs and operations go out at once, and a burst adds one trailing refresh`() = runTest {
        val raw = Channel<RawChange>(Channel.UNLIMITED)
        raw.consumeAsFlow().coalesce().test {
            raw.send(refs)
            assertEquals(RepoChange.RefsChanged, awaitItem())
            repeat(20) { raw.send(refs) } // a fetch updating twenty refs
            raw.send(operation)
            assertEquals(RepoChange.OperationChanged, awaitItem())
            expectNoEvents()
            advanceTimeBy(60.milliseconds)
            assertEquals(RepoChange.RefsChanged, awaitItem())
            expectNoEvents()
            raw.close()
            awaitComplete()
        }
    }

    @Test
    fun `an overflow is a full refresh, and replaces a pending status refresh`() = runTest {
        val raw = Channel<RawChange>(Channel.UNLIMITED)
        raw.consumeAsFlow().coalesce().test {
            raw.send(status)
            advanceTimeBy(100.milliseconds)
            raw.send(RawChange.Overflow)
            assertEquals(RepoChange.FullRefresh(RepoChange.FullRefresh.Reason.OVERFLOW), awaitItem())
            advanceTimeBy(1.seconds)
            expectNoEvents()
            raw.close()
            awaitComplete()
        }
    }

    @Test
    fun `a fallback to polling is passed on as is`() = runTest {
        val fallback = RawChange.Fallback(RepoChange.PollingFallback.Reason.WATCH_LIMIT, "inotify limit")
        flow { emit(fallback) }.coalesce().test {
            assertEquals(
                RepoChange.PollingFallback(RepoChange.PollingFallback.Reason.WATCH_LIMIT, "inotify limit"),
                awaitItem(),
            )
            awaitComplete()
        }
    }

    @Test
    fun `waking from sleep is a full refresh`() = runTest {
        var wall = 0L
        val ticks = wakeChecks(interval = 5.seconds, threshold = 30.seconds, wallClockMillis = { wall })
        ticks.test {
            wall += 5_000
            advanceTimeBy(5.seconds + 1.milliseconds)
            expectNoEvents()
            // The machine slept for ten minutes: the wall clock moved, the coroutine clock didn't.
            wall += 10.minutes.inWholeMilliseconds + 5_000
            advanceTimeBy(5.seconds)
            assertEquals(RawChange.Wake, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the status debounce restarts with each event`() = runTest {
        val raw = Channel<RawChange>(Channel.UNLIMITED)
        val seen = mutableListOf<Pair<Long, RepoChange>>()
        val job = launch { raw.consumeAsFlow().coalesce().collect { seen += testScheduler.currentTime to it } }
        raw.send(status)
        advanceTimeBy(200.milliseconds)
        raw.send(status)
        advanceTimeBy(200.milliseconds)
        raw.send(status)
        advanceTimeBy(400.milliseconds)
        raw.close()
        job.join()
        assertEquals(listOf<Pair<Long, RepoChange>>(700L to RepoChange.StatusChanged), seen)
    }
}
