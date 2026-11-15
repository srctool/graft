// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class WriteQueueTest {
    @Test
    fun `writes to one repository never overlap, writes to two do`() = runTest {
        val queue = WriteQueue()
        val running = mutableMapOf("a" to AtomicInteger(), "b" to AtomicInteger())
        var maxA = 0
        var maxBoth = 0
        val total = AtomicInteger()
        (1..6)
            .map { i ->
                val repo = if (i % 2 == 0) "a" else "b"
                async {
                    queue.withWrite(repo) {
                        val now = running.getValue(repo).incrementAndGet()
                        val all = total.incrementAndGet()
                        if (repo == "a") maxA = maxOf(maxA, now)
                        maxBoth = maxOf(maxBoth, all)
                        delay(100)
                        total.decrementAndGet()
                        running.getValue(repo).decrementAndGet()
                    }
                }
            }.awaitAll()
        assertEquals(1, maxA)
        assertEquals(2, maxBoth)
    }
}
