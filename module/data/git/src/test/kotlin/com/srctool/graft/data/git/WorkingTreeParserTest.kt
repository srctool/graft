// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.GitParseException
import com.srctool.graft.data.git.api.models.ChangeEntry
import com.srctool.graft.data.git.api.models.ChangeStatus
import com.srctool.graft.data.git.api.models.ConflictKind
import com.srctool.graft.data.git.api.models.SubmoduleChange
import com.srctool.graft.data.git.implementation.source.local.WorkingTreeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `status --porcelain=v2 -z` read into changes: each line shape, on bytes git would write. */
class WorkingTreeParserTest {
    private val h = "1".repeat(40)
    private val i = "2".repeat(40)

    private fun records(vararg lines: String): ByteArray =
        lines.joinToString("") { it + "\u0000" }.toByteArray(Charsets.ISO_8859_1)

    private fun parse(vararg lines: String) = WorkingTreeParser.parse(records(*lines)).entries

    private fun ordinary(xy: String, path: String, sub: String = "N...", m: String = "100644 100644 100644") =
        "1 $xy $sub $m $h $i $path"

    @Test
    fun `an ordinary entry has an index side and a worktree side`() {
        val entries =
            parse(ordinary("M.", "staged.txt"), ordinary(".M", "unstaged.txt"), ordinary("MM", "both.txt"))
        assertEquals(
            listOf(
                ChangeStatus.MODIFIED to null,
                null to ChangeStatus.MODIFIED,
                ChangeStatus.MODIFIED to ChangeStatus.MODIFIED,
            ),
            entries.map { it.index to it.worktree },
        )
        assertEquals(listOf("staged.txt", "unstaged.txt", "both.txt"), entries.map { it.path })
    }

    @Test
    fun `every status letter is read`() {
        val letters = mapOf(
            'M' to ChangeStatus.MODIFIED,
            'T' to ChangeStatus.TYPE_CHANGED,
            'A' to ChangeStatus.ADDED,
            'D' to ChangeStatus.DELETED,
        )
        letters.forEach { (letter, status) ->
            assertEquals(status, parse(ordinary("$letter.", "f")).single().index, "index $letter")
            assertEquals(status, parse(ordinary(".$letter", "f")).single().worktree, "worktree $letter")
        }
    }

    @Test
    fun `a rename takes its original path from the next record, which is not a change of its own`() {
        val entries =
            parse(
                "2 R. N... 100644 100644 100644 $h $i R100 new name.txt",
                "old name.txt",
                ordinary(".M", "after.txt"),
            )
        assertEquals(2, entries.size)
        val rename = entries.first()
        assertEquals("new name.txt", rename.path)
        assertEquals("old name.txt", rename.originalPath)
        assertEquals(ChangeStatus.RENAMED, rename.index)
        assertEquals("after.txt", entries.last().path)
    }

    @Test
    fun `a copy and a rename with edits are read`() {
        val entries =
            parse(
                "2 C. N... 100644 100644 100644 $h $i C75 copy.txt",
                "orig.txt",
                "2 RM N... 100644 100644 100644 $h $i R90 moved.txt",
                "was.txt",
            )
        assertEquals(ChangeStatus.COPIED, entries[0].index)
        assertEquals(ChangeStatus.RENAMED to ChangeStatus.MODIFIED, entries[1].index to entries[1].worktree)
        assertEquals("was.txt", entries[1].originalPath)
    }

    @Test
    fun `untracked and ignored entries are their own kinds`() {
        val entries = parse("? new.txt", "? dir/inner.txt", "! build/")
        assertTrue(entries[0].untracked)
        assertEquals("dir/inner.txt", entries[1].path)
        assertTrue(entries[2].ignored)
        assertFalse(entries[2].untracked)
        assertNull(entries[0].index)
        assertNull(entries[0].worktree)
    }

    @Test
    fun `a path with spaces, quotes and a leading dash is kept whole`() {
        val tricky = listOf("with space.txt", "quote\"d.txt", "-dash.txt", "tab\there.txt", "ünï/cødé.txt")
        val entries = parse(*tricky.map { ordinary(".M", it) }.toTypedArray())
        assertEquals(tricky, entries.map { it.path })
    }

    @Test
    fun `a path that is not UTF-8 is read as Windows-1252, as the diff paths are`() {
        // "caf\xE9.txt": Latin-1 bytes, invalid as UTF-8.
        val bytes = ("? caf".toByteArray() + byteArrayOf(0xE9.toByte()) + ".txt\u0000".toByteArray())
        assertEquals("café.txt", WorkingTreeParser.parse(bytes).entries.single().path)
    }

    @Test
    fun `a path in UTF-8 is read as UTF-8`() {
        val bytes = "? café.txt\u0000".toByteArray(Charsets.UTF_8)
        assertEquals("café.txt", WorkingTreeParser.parse(bytes).entries.single().path)
    }

    @Test
    fun `unmerged entries say how each side changed`() {
        val kinds =
            mapOf(
                "UU" to ConflictKind.BOTH_MODIFIED,
                "AA" to ConflictKind.BOTH_ADDED,
                "DD" to ConflictKind.BOTH_DELETED,
                "AU" to ConflictKind.ADDED_BY_US,
                "UA" to ConflictKind.ADDED_BY_THEM,
                "DU" to ConflictKind.DELETED_BY_US,
                "UD" to ConflictKind.DELETED_BY_THEM,
            )
        kinds.forEach { (xy, kind) ->
            val entry =
                parse("u $xy N... 100644 100644 100644 100644 $h $h $i conflicted file.txt").single()
            assertEquals(kind, entry.conflict, xy)
            assertEquals("conflicted file.txt", entry.path)
            assertNull(entry.index)
            assertNull(entry.worktree)
        }
    }

    @Test
    fun `a submodule says what changed inside it`() {
        val sub = "160000 160000 160000"
        val entries =
            parse(
                ordinary(".M", "moved", sub = "SC..", m = sub),
                ordinary(".M", "dirty", sub = "S.M.", m = sub),
                ordinary(".M", "untracked", sub = "S..U", m = sub),
                ordinary(".M", "all", sub = "SCMU", m = sub),
                ordinary(".M", "plain"),
            )
        assertEquals(
            SubmoduleChange(commitChanged = true, trackedModified = false, untracked = false),
            entries[0].submodule,
        )
        assertEquals(SubmoduleChange(false, trackedModified = true, false), entries[1].submodule)
        assertEquals(SubmoduleChange(false, false, untracked = true), entries[2].submodule)
        assertEquals(SubmoduleChange(true, true, true), entries[3].submodule)
        assertNull(entries[4].submodule)
    }

    @Test
    fun `a change of the executable bit is a mode change, and only that`() {
        val entry = parse(ordinary(".M", "run.sh", m = "100644 100644 100755")).single()
        assertEquals("100644", entry.modes?.head)
        assertEquals("100644", entry.modes?.index)
        assertEquals("100755", entry.modes?.worktree)
        assertTrue(entry.modes?.unstagedChange == true)
        assertFalse(entry.modes?.stagedChange == true)
        val staged = parse(ordinary("M.", "run.sh", m = "100644 100755 100755")).single()
        assertTrue(staged.modes?.stagedChange == true)
    }

    @Test
    fun `a file in no changed mode has no mode change`() {
        val entry = parse(ordinary(".M", "f.txt")).single()
        assertFalse(entry.modes?.stagedChange == true)
        assertFalse(entry.modes?.unstagedChange == true)
    }

    @Test
    fun `branch headers are skipped`() {
        val entries = parse("# branch.oid $h", "# branch.head main", ordinary(".M", "a.txt"))
        assertEquals(listOf("a.txt"), entries.map { it.path })
    }

    @Test
    fun `nothing changed is an empty list`() {
        assertEquals(emptyList<ChangeEntry>(), parse())
        assertEquals(emptyList<ChangeEntry>(), WorkingTreeParser.parse(ByteArray(0)).entries)
    }

    @Test
    fun `the views split entries by side`() {
        val tree =
            WorkingTreeParser.parse(
                records(
                    ordinary("M.", "staged.txt"),
                    ordinary(".M", "unstaged.txt"),
                    ordinary("MM", "both.txt"),
                    "? new.txt",
                    "! out/",
                    "u UU N... 100644 100644 100644 100644 $h $h $i c.txt",
                ),
            )
        assertEquals(listOf("staged.txt", "both.txt"), tree.staged.map { it.path })
        assertEquals(listOf("unstaged.txt", "both.txt"), tree.unstaged.map { it.path })
        assertEquals(listOf("new.txt"), tree.untracked.map { it.path })
        assertEquals(listOf("out/"), tree.ignored.map { it.path })
        assertEquals(listOf("c.txt"), tree.conflicted.map { it.path })
    }

    @Test
    fun `output git would not write is a parse error`() {
        assertFailsWith<GitParseException> { parse("x what is this") }
        assertFailsWith<GitParseException> { parse("1 M.") }
        assertFailsWith<GitParseException> { parse("1 Q. N... 100644 100644 100644 $h $i f") }
        assertFailsWith<GitParseException> { parse("2 R. N... 100644 100644 100644 $h $i R100 only-new") }
        assertFailsWith<GitParseException> { parse("u ZZ N... 100644 100644 100644 100644 $h $h $i f") }
    }
}
