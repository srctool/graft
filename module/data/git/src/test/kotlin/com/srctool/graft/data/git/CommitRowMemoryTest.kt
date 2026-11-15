// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.data.git.api.models.CommitRow
import com.srctool.graft.data.git.api.models.People
import com.srctool.graft.data.git.implementation.source.local.LogParser
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Retained heap per [CommitRow] at 500k rows (Commit Graph: < 400 MB for the whole graph). The budget is the G1 Task 5
 * baseline plus 10%: a field added to the row must stay small.
 */
class CommitRowMemoryTest {
    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        repeat(4) {
            System.gc()
            Thread.sleep(50)
        }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    @Test
    fun `a row costs no more than its budget`() {
        val bytes = LogParserBenchmarkTest().stream()
        val before = usedHeap()
        val rows: List<CommitRow> = LogParser.parse(bytes.inputStream(), People()).toList()
        val after = usedHeap()
        val perRow = (after - before) / rows.size
        println("CommitRow: ${rows.size} rows retain ${(after - before) / 1_000_000} MB, $perRow bytes per row")
        assertTrue(perRow <= BUDGET_BYTES, "a row retains $perRow bytes, budget $BUDGET_BYTES")
        // Keep the rows reachable until after the measurement.
        assertTrue(rows.isNotEmpty())
    }

    private companion object {
        // 374 bytes measured before the author type was added (MacBook, JDK 21, G1), plus 10%.
        const val BUDGET_BYTES = 411L
    }
}
