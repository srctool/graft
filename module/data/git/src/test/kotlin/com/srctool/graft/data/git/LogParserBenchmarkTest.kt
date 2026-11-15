// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.data.git.api.models.People
import com.srctool.graft.data.git.implementation.source.local.LogParser
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parses a synthetic 500k-commit `git log` stream (the spike's repository size) and prints the time. The assertion is
 * loose so slow CI runners don't flake; the measured time goes into the ledger.
 */
class LogParserBenchmarkTest {
    private val rowsCount = 500_000

    fun stream(): ByteArray {
        val out = ByteArrayOutputStream(rowsCount * 160)
        val nul = 0
        fun hash(i: Int) = "%040x".format(i.toLong() + 1)
        for (i in 0 until rowsCount) {
            out.write(hash(i).toByteArray())
            out.write(nul)
            if (i + 1 < rowsCount) out.write(hash(i + 1).toByteArray())
            out.write(nul)
            out.write("Author ${i % 50}".toByteArray())
            out.write(nul)
            out.write("a${i % 50}@example.invalid".toByteArray())
            out.write(nul)
            out.write("1767225600".toByteArray())
            out.write(nul)
            out.write("Author ${i % 50}".toByteArray())
            out.write(nul)
            out.write("1767225600".toByteArray())
            out.write(nul)
            out.write("Commit subject number $i with some typical length".toByteArray())
            out.write(nul)
            out.write(nul) // no co-author trailers
            out.write(nul)
        }
        return out.toByteArray()
    }

    @Test
    fun `parses 500k rows`() {
        val bytes = stream()
        repeat(2) { LogParser.parse(bytes.inputStream(), People()).count() } // warm up the JIT
        val start = System.nanoTime()
        val people = People()
        val count = LogParser.parse(bytes.inputStream(), people).count()
        val millis = (System.nanoTime() - start) / 1_000_000
        println("LogParser: $count rows (${bytes.size / 1_000_000} MB) in $millis ms")
        assertEquals(rowsCount, count)
        // 50 authors with their email, and the same 50 as committers (name only).
        assertEquals(100, people.size)
        assertTrue(millis < 10_000, "parsing $rowsCount rows took $millis ms")
    }
}
