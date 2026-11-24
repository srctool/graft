// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.git

import com.srctool.graft.core.ui.git.DiffLineKind.ADDED
import com.srctool.graft.core.ui.git.DiffLineKind.CONTEXT
import com.srctool.graft.core.ui.git.DiffLineKind.REMOVED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiffSelectionTest {
    private val document =
        DiffDocument(
            "A.kt",
            listOf(
                DiffHunkView(
                    "@@ -1,4 +1,4 @@",
                    listOf(
                        DiffLineView(CONTEXT, 1, 1, "a"),
                        DiffLineView(REMOVED, 2, null, "b"),
                        DiffLineView(ADDED, null, 2, "B"),
                        DiffLineView(ADDED, null, 3, "C"),
                        DiffLineView(CONTEXT, 3, 4, "d"),
                    ),
                ),
                DiffHunkView(
                    "@@ -9,2 +10,2 @@",
                    listOf(DiffLineView(REMOVED, 9, null, "x"), DiffLineView(CONTEXT, 10, 10, "y")),
                ),
                DiffHunkView("@@ -20,1 +21,1 @@", listOf(DiffLineView(CONTEXT, 20, 21, "z"))),
            ),
        )

    @Test
    fun `only added and removed lines can be ticked`() {
        assertEquals(listOf(1, 2, 3), document.changedLines(0).map { it.line })
        assertEquals(listOf(DiffLineRef(1, 0)), document.changedLines(1))
        assertEquals(emptyList(), document.changedLines(2))
        assertEquals(4, document.allChangedLines().size)
    }

    @Test
    fun `a hunk is none, partly or all ticked`() {
        assertEquals(DiffCheck.NONE, document.checkOf(0, emptySet()))
        assertEquals(DiffCheck.PARTIAL, document.checkOf(0, setOf(DiffLineRef(0, 2))))
        assertEquals(DiffCheck.ALL, document.checkOf(0, document.changedLines(0).toSet()))
        assertEquals(DiffCheck.NONE, document.checkOf(2, document.allChangedLines()), "no changes, nothing to tick")
    }

    @Test
    fun `toggling a hunk ticks all of it unless all of it was ticked`() {
        val partly = setOf(DiffLineRef(0, 1), DiffLineRef(1, 0))
        val all = document.toggleHunk(0, partly)
        assertEquals(DiffCheck.ALL, document.checkOf(0, all))
        assertTrue(DiffLineRef(1, 0) in all, "other hunks are left alone")
        val none = document.toggleHunk(0, all)
        assertEquals(DiffCheck.NONE, document.checkOf(0, none))
        assertTrue(DiffLineRef(1, 0) in none)
    }

    @Test
    fun `toggling a line flips it`() {
        val line = DiffLineRef(0, 3)
        assertEquals(setOf(line), toggleLine(line, emptySet()))
        assertEquals(emptySet(), toggleLine(line, setOf(line)))
    }

    @Test
    fun `rows know their hunk and their lines in both layouts`() {
        val unified = diffRows(document, DiffMode.UNIFIED)
        assertEquals(listOf(0, 1, 2), unified.filterIsInstance<DiffRow.Header>().map { it.hunk })
        val refs = unified.filterIsInstance<DiffRow.Unified>().mapNotNull { it.ref }
        assertEquals(document.allChangedLines().toList(), refs)
        assertNull(unified.filterIsInstance<DiffRow.Unified>().first { it.line.kind == CONTEXT }.ref)

        val split = diffRows(document, DiffMode.SIDE_BY_SIDE).filterIsInstance<DiffRow.Split>()
        val paired = split.first { it.leftRef != null && it.rightRef != null }
        assertEquals(DiffLineRef(0, 1), paired.leftRef)
        assertEquals(DiffLineRef(0, 2), paired.rightRef)
        val extra = split.first { it.right?.text == "C" }
        assertNull(extra.leftRef)
        assertEquals(DiffLineRef(0, 3), extra.rightRef)
        assertEquals(document.allChangedLines(), split.flatMap { it.refs }.toSet())
    }
}
