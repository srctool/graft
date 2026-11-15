// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.data.git.api.models.ChangedFile
import com.srctool.graft.data.git.api.models.FileStatus
import com.srctool.graft.data.git.implementation.source.local.PatchParser
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parses a synthetic large patch (200 files, each one hunk of 1,000 removed and 1,000 added lines plus context) and
 * prints the time. The assertion is loose so slow CI runners don't flake; the measured time goes into the ledger.
 */
class PatchParserBenchmarkTest {
    private val fileCount = 200
    private val changed = 1_000
    private val context = 3

    private fun patch(): Pair<ByteArray, List<ChangedFile>> {
        val out = ByteArrayOutputStream(fileCount * changed * 90)
        val files = ArrayList<ChangedFile>()
        repeat(fileCount) { f ->
            val path = "src/module$f/File$f.kt"
            files += ChangedFile(FileStatus.MODIFIED, path, null, null, "100644", "100644", changed, changed)
            val count = changed + 2 * context
            out.write(
                "diff --git a/$path b/$path\nindex 1111111..2222222 100644\n--- a/$path\n+++ b/$path\n".toByteArray(),
            )
            out.write("@@ -1,$count +1,$count @@ class File$f\n".toByteArray())
            repeat(context) { out.write(" context line $it of a typical length for source code\n".toByteArray()) }
            repeat(changed) {
                out.write("-    val removed$it = compute(argument$it, another$it) // old\n".toByteArray())
            }
            repeat(changed) {
                out.write("+    val added$it = computeBetter(argument$it, another$it) // new\n".toByteArray())
            }
            repeat(context) { out.write(" trailing context line $it of a typical length\n".toByteArray()) }
        }
        return out.toByteArray() to files
    }

    @Test
    fun `parses a 400,000-line patch`() {
        val (bytes, files) = patch()
        repeat(2) { PatchParser.parse(bytes, files) } // warm up the JIT
        val start = System.nanoTime()
        val diffs = PatchParser.parse(bytes, files)
        val millis = (System.nanoTime() - start) / 1_000_000
        val lines = diffs.sumOf { d -> d.hunks.sumOf { it.lines.size } }
        println("PatchParser: $lines lines in ${bytes.size / 1_000_000} MB, $fileCount files, in $millis ms")
        assertEquals(fileCount * (2 * changed + 2 * context), lines)
        assertTrue(millis < 10_000, "parsing took $millis ms")
    }
}
