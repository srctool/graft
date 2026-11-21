// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.data.git.implementation.source.local.WorkingTreeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The status parser on 50,000 entries, the size of a big working tree's status (Task 2's benchmark). */
class WorkingTreeBenchmarkTest {
    @Test
    fun `50,000 entries parse quickly`() {
        val h = "1".repeat(40)
        val text =
            buildString {
                repeat(20_000) {
                    append("1 .M N... 100644 100644 100644 $h $h src/module$it/some/deeper/path/File$it.kt\u0000")
                }
                repeat(10_000) { append("1 M. N... 100644 100644 100644 $h $h src/staged$it/File$it.kt\u0000") }
                repeat(5_000) {
                    append("2 R. N... 100644 100644 100644 $h $h R100 new/dir$it/n.kt\u0000old/dir$it/o.kt\u0000")
                }
                repeat(15_000) { append("? generated/build$it/output-$it.bin\u0000") }
            }
        val bytes = text.toByteArray()
        repeat(2) { WorkingTreeParser.parse(bytes) } // warm up
        val started = System.nanoTime()
        val tree = WorkingTreeParser.parse(bytes)
        val ms = (System.nanoTime() - started) / 1_000_000
        println("GRAFT_BENCH status.parse50k.ms=$ms")
        assertEquals(50_000, tree.entries.size)
        assertEquals(5_000, tree.entries.count { it.originalPath != null })
        // A loose bound so a slow CI runner doesn't flake; the dev-machine time goes in the ledger.
        assertTrue(ms < 5_000, "took $ms ms")
    }
}
