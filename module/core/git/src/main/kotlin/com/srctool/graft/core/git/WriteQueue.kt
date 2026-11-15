// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * One mutating git operation at a time per repository (ADR-3). Git serializes on `index.lock` and ref locks, so
 * parallel writes would fail at random; queueing them here makes them wait instead. [Mutex] is fair, so writes run
 * in the order they arrived.
 */
public class WriteQueue {
    private val locks = ConcurrentHashMap<String, Mutex>()

    public suspend fun <T> withWrite(repository: String, block: suspend () -> T): T =
        locks.computeIfAbsent(repository) { Mutex() }.withLock { block() }
}
