// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.GitParseException
import com.srctool.graft.data.git.api.models.ChangedFile
import com.srctool.graft.data.git.api.models.DiffLine.Kind.ADDED
import com.srctool.graft.data.git.api.models.DiffLine.Kind.CONTEXT
import com.srctool.graft.data.git.api.models.DiffLine.Kind.REMOVED
import com.srctool.graft.data.git.api.models.FileDiff
import com.srctool.graft.data.git.api.models.FileStatus
import com.srctool.graft.data.git.api.models.SignatureStatus
import com.srctool.graft.data.git.api.models.Trailer
import com.srctool.graft.data.git.implementation.source.local.ChangedFilesParser
import com.srctool.graft.data.git.implementation.source.local.DetailsParser
import com.srctool.graft.data.git.implementation.source.local.PatchParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The details, file-list and patch parsers on output recorded from `DiffRepo` (G1 Task 4). */
class DiffParserTest {
    private fun files(case: String) = ChangedFilesParser.parse(Fixtures.read("files", case))

    private fun diff(case: String): Map<String, FileDiff> =
        PatchParser.parse(Fixtures.read("patch", case), files(case)).associateBy { it.file.path }

    private fun List<ChangedFile>.of(path: String) = single { it.path == path }

    @Test
    fun `every kind of change is listed with its status, modes and counts`() {
        val f = files("change")
        assertEquals(
            listOf("a.txt", "added.txt", "big.txt", "gone.txt", "lib/New.kt", "noeol.txt", "pic.bin", "run.sh", "sub"),
            f.map { it.path },
        )
        with(f.of("a.txt")) { assertEquals(FileStatus.MODIFIED to (2 to 2), status to (additions to deletions)) }
        assertEquals(FileStatus.ADDED, f.of("added.txt").status)
        assertEquals(FileStatus.DELETED, f.of("gone.txt").status)
        with(f.of("lib/New.kt")) {
            assertEquals(FileStatus.RENAMED, status)
            assertEquals("lib/Old.kt", oldPath)
            assertEquals(95, similarity)
            assertEquals(1 to 0, additions to deletions)
        }
        with(f.of("pic.bin")) {
            assertTrue(isBinary)
            assertNull(additions)
        }
        with(f.of("run.sh")) {
            assertTrue(modeChanged)
            assertEquals("100644" to "100755", oldMode to newMode)
            assertEquals(0 to 0, additions to deletions)
        }
        assertTrue(f.of("sub").isSubmodule)
        assertTrue(f.none { it.path != "run.sh" && it.modeChanged })
        // Object ids: none before an addition or after a deletion; the same blob on both sides of a mode change.
        assertNull(f.of("added.txt").oldObject)
        assertNull(f.of("gone.txt").newObject)
        assertEquals(f.of("run.sh").oldObject, f.of("run.sh").newObject)
    }

    @Test
    fun `a root commit lists every file as added, the gitlink too`() {
        val f = files("base")
        assertTrue(f.all { it.status == FileStatus.ADDED }, f.toString())
        assertTrue(f.of("sub").isSubmodule)
        assertEquals(10_000, f.of("big.txt").additions)
    }

    @Test
    fun `hunks carry numbered lines on each side`() {
        val a = diff("change").getValue("a.txt")
        assertEquals(2, a.hunks.size)
        val first = a.hunks[0]
        assertEquals(1 to 5, first.oldStart to first.oldCount)
        val removed = first.lines.single { it.kind == REMOVED }
        val added = first.lines.single { it.kind == ADDED }
        assertEquals("line 2" to 2, removed.text to removed.oldNumber)
        assertEquals("LINE 2" to 2, added.text to added.newNumber)
        assertNull(removed.newNumber)
        // Context and removed lines have an old number, added lines don't.
        assertEquals(listOf(1, 2, null, 3, 4, 5), first.lines.map { it.oldNumber })
        assertEquals(listOf(1, null, 2, 3, 4, 5), first.lines.map { it.newNumber })
        assertEquals("LINE 18", a.hunks[1].lines.single { it.kind == ADDED }.text)
    }

    @Test
    fun `edits deep in a 10,000-line file are two hunks with the right numbers`() {
        val big = diff("change").getValue("big.txt")
        assertEquals(listOf(5000, 9000), big.hunks.map { h -> h.lines.single { it.kind == ADDED }.newNumber })
        assertEquals(4997, big.hunks[0].oldStart)
    }

    @Test
    fun `a lost final newline is marked on the line it's missing from`() {
        val lines = diff("change").getValue("noeol.txt").hunks.single().lines
        assertEquals(false, lines.single { it.kind == REMOVED }.noNewlineAtEnd)
        assertEquals(true, lines.single { it.kind == ADDED }.noNewlineAtEnd)
        assertEquals("end", lines.single { it.kind == ADDED }.text)
    }

    @Test
    fun `binary, mode-only and gitlink changes`() {
        val d = diff("change")
        assertTrue(d.getValue("pic.bin").hunks.isEmpty())
        assertTrue(d.getValue("run.sh").hunks.isEmpty())
        val sub = d.getValue("sub").hunks.single().lines
        assertEquals(
            listOf(
                REMOVED to "Subproject commit 1111111111111111111111111111111111111111",
                ADDED to "Subproject commit 2222222222222222222222222222222222222222",
            ),
            sub.map { it.kind to it.text },
        )
    }

    @Test
    fun `a rename with an edit, an addition and a deletion`() {
        val d = diff("change")
        val renamed = d.getValue("lib/New.kt").hunks.single().lines
        assertEquals("val y = 1", renamed.single { it.kind == ADDED }.text)
        assertTrue(renamed.filter { it.kind == CONTEXT }.all { it.text.startsWith("val x =") })
        assertTrue(d.getValue("added.txt").hunks.single().lines.all { it.kind == ADDED })
        assertEquals(listOf("bye"), d.getValue("gone.txt").hunks.single().lines.map { it.text })
    }

    @Test
    fun `a Latin-1 path and content read as Windows-1252`() {
        val f = files("latin1").single()
        assertEquals(DiffRepo.LATIN1_NAME, f.path)
        val line = PatchParser.parse(Fixtures.read("patch", "latin1"), listOf(f)).single().hunks.single().lines.single()
        assertEquals(DiffRepo.LATIN1_TEXT, line.text)
    }

    @Test
    fun `commit details hold people, dates, trailers, signature and message`() {
        val d = DetailsParser.parse(Fixtures.read("details", "change")).withFiles(files("change"))
        assertEquals("Graft Test", d.author.name)
        assertEquals("test@graft.invalid", d.committer.email)
        assertEquals(1767225600L, d.author.time)
        assertEquals(0, d.author.offsetMinutes)
        assertEquals(1, d.parents.size)
        assertEquals(SignatureStatus.NONE, d.signature)
        assertEquals(
            listOf(
                Trailer("Co-authored-by", DiffRepo.TRAILER_AUTHOR),
                Trailer("Signed-off-by", "Graft Test <test@graft.invalid>"),
            ),
            d.trailers,
        )
        assertEquals("Change many things", d.subject)
        assertTrue(d.message.contains("The body explains why."))
        assertEquals(9, d.files.size)
    }

    @Test
    fun `a patch that doesn't match its file list is a parse error`() {
        assertFailsWith<GitParseException> { PatchParser.parse(Fixtures.read("patch", "change"), files("base")) }
        val cut = Fixtures.read("patch", "change").let { it.copyOf(it.size / 2) }
        assertFailsWith<GitParseException> { PatchParser.parse(cut, files("change")) }
    }

    @Test
    fun `a time zone east and west of UTC`() {
        val record = { zone: String ->
            "0000000000000000000000000000000000000001\u0000\u0000A\u0000a@x\u00001767225600 $zone\u0000" +
                "C\u0000c@x\u00001767225600 $zone\u0000N\u0000\u0000subject\n"
        }
        assertEquals(330, DetailsParser.parse(record("+0530").toByteArray()).author.offsetMinutes)
        assertEquals(-480, DetailsParser.parse(record("-0800").toByteArray()).committer.offsetMinutes)
    }
}
