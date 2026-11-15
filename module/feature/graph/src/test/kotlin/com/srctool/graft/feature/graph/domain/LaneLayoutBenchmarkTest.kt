// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.domain

import com.srctool.graft.data.git.api.models.CommitRow
import com.srctool.graft.data.git.api.models.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lays out synthetic histories of 10k and 500k commits (the Commit Graph note's targets) and prints the times. The
 * assertions are loose so slow CI runners don't flake; the measured times go into the ledger.
 */
class LaneLayoutBenchmarkTest {
    @Test
    fun `lays out 10k and 500k commits`() {
        val small = synthetic(10_000)
        val large = synthetic(500_000)
        repeat(3) { LaneLayout().let { lanes -> large.forEach(lanes::next) } } // warm up the JIT

        val smallMillis = time { LaneLayout().let { lanes -> small.forEach(lanes::next) } }
        var edges = 0L
        var widest = 0
        val largeMillis =
            time {
                val lanes = LaneLayout()
                large.forEach {
                    val row = lanes.next(it)
                    edges += row.edgeCount
                    widest = maxOf(widest, row.width)
                }
            }
        println(
            "LaneLayout: 10k rows in $smallMillis ms; 500k rows in $largeMillis ms ($edges edges, $widest lanes wide)",
        )
        // A merged parent nobody awaits yet opens a ninth lane, which rejoins its branch at that parent.
        assertEquals(LANES + 1, widest)
        assertTrue(largeMillis < 10_000, "laying out 500k rows took $largeMillis ms")
    }

    private inline fun time(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    companion object {
        private const val LANES = 8

        /**
         * [size] commits on [LANES] parallel branches in topological order: commit `i` has first parent `i + 8`, and
         * every 37th commit also merges `i + 9` from the neighboring branch. The last eight commits are roots.
         */
        fun synthetic(size: Int): List<CommitRow> {
            val ids = Array(size) { ObjectId("%040x".format(it + 1L)) }
            return List(size) { i ->
                val parents =
                    buildList {
                        if (i + LANES < size) add(ids[i + LANES])
                        if (i % 37 == 0 && i + LANES + 1 < size) add(ids[i + LANES + 1])
                    }
                CommitRow(ids[i], parents, 0, 0, 0, 0, "c$i")
            }
        }
    }
}
