// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.watcher

import com.srctool.graft.core.watcher.RepoChange.FullRefresh.Reason
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** One event from the OS (or the poller, or the wake check), before coalescing. */
internal sealed interface RawChange {
    data class Path(val kind: ChangeKind) : RawChange

    data object Overflow : RawChange

    data object Wake : RawChange

    data class Fallback(val reason: RepoChange.PollingFallback.Reason, val detail: String) : RawChange
}

/**
 * Raw events into the few refreshes a session acts on (Platform Integration 3.1):
 * - refs and operations go out at once; more of the same within [burst] add one trailing refresh, so a fetch that
 *   updates fifty refs costs two refreshes, not fifty;
 * - status waits until no status event came for [statusDebounce];
 * - an overflow or a wake is a full refresh, which also covers any status refresh still waiting.
 */
internal fun Flow<RawChange>.coalesce(
    statusDebounce: Duration = 300.milliseconds,
    burst: Duration = 50.milliseconds,
): Flow<RepoChange> = channelFlow {
    var pendingStatus: Job? = null
    val windowOpen = mutableSetOf<ChangeKind>()
    val trailing = mutableSetOf<ChangeKind>()

    fun changeOf(kind: ChangeKind): RepoChange = when (kind) {
        ChangeKind.REFS -> RepoChange.RefsChanged
        ChangeKind.OPERATION -> RepoChange.OperationChanged
        ChangeKind.STATUS -> RepoChange.StatusChanged
    }

    collect { raw ->
        when (raw) {
            RawChange.Overflow, RawChange.Wake -> {
                pendingStatus?.cancel()
                pendingStatus = null
                send(RepoChange.FullRefresh(if (raw == RawChange.Wake) Reason.WAKE else Reason.OVERFLOW))
            }

            is RawChange.Fallback -> send(RepoChange.PollingFallback(raw.reason, raw.detail))

            is RawChange.Path -> when (raw.kind) {
                ChangeKind.STATUS -> {
                    pendingStatus?.cancel()
                    pendingStatus =
                        launch {
                            delay(statusDebounce)
                            send(RepoChange.StatusChanged)
                        }
                }

                else -> {
                    val kind = raw.kind
                    if (kind in windowOpen) {
                        trailing += kind
                    } else {
                        windowOpen += kind
                        send(changeOf(kind))
                        launch {
                            delay(burst)
                            windowOpen -= kind
                            if (trailing.remove(kind)) send(changeOf(kind))
                        }
                    }
                }
            }
        }
    }
}

/**
 * A [RawChange.Wake] each time the wall clock moved much more than the coroutine clock: the machine slept, and the
 * events that came in the meantime can't be trusted (Platform Integration 3.1).
 */
internal fun wakeChecks(
    interval: Duration = 5.seconds,
    threshold: Duration = 30.seconds,
    wallClockMillis: () -> Long = System::currentTimeMillis,
): Flow<RawChange> = flow {
    while (true) {
        val before = wallClockMillis()
        delay(interval)
        if (wallClockMillis() - before > (interval + threshold).inWholeMilliseconds) emit(RawChange.Wake)
    }
}
