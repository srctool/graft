// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.git

import com.srctool.graft.core.ui.git.DiffLineKind.ADDED
import com.srctool.graft.core.ui.git.DiffLineKind.CONTEXT
import com.srctool.graft.core.ui.git.DiffLineKind.REMOVED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitWidgetLogicTest {
    @Test
    fun `initials take the first and last words, in any script, without bot or email parts`() {
        assertEquals("DR", initials("Dwiki Riyadi"))
        assertEquals("AL", initials("ann van der lee"))
        assertEquals("A", initials("ann"))
        assertEquals("D", initials("dependabot[bot]"))
        assertEquals("JD", initials("jane.doe@example.com"))
        assertEquals("ÉZ", initials("élodie zoë"))
        assertEquals("李", initials("李"))
        assertEquals("", initials("  42 "))
    }

    @Test
    fun `a ref label reads name, merged remote, then ahead and behind`() {
        assertEquals("main", refLabelText(RefLabel("main", RefLabelKind.LOCAL)))
        assertEquals(
            "main ⇄ origin/main  ↑2 ↓3",
            refLabelText(RefLabel("main", RefLabelKind.LOCAL, mergedRemote = "origin/main", ahead = 2, behind = 3)),
        )
        assertEquals("↓3", aheadBehind(0, 3))
        assertEquals("", aheadBehind(0, 0))
    }

    @Test
    fun `word diff marks changed words whole, and nothing when the line was rewritten`() {
        val (old, new) = WordDiff.diff("val timeout = 30", "val timeout = 60")
        assertEquals(listOf(14..15), old)
        assertEquals(listOf(14..15), new)

        val (removed, added) = WordDiff.diff("fun login(user)", "fun login(user, retry)")
        assertEquals(emptyList(), removed)
        assertEquals("fun login(user, retry)".substring(added.single()), ", retry")

        val rewritten = WordDiff.diff("import foo.Bar", "return 42 * x")
        assertEquals(emptyList<IntRange>() to emptyList(), rewritten)
        assertEquals(emptyList<IntRange>() to emptyList(), WordDiff.diff("same", "same"))
    }

    private val document =
        DiffDocument(
            "src/Login.kt",
            listOf(
                DiffHunkView(
                    "@@ -1,5 +1,5 @@",
                    listOf(
                        DiffLineView(CONTEXT, 1, 1, "fun login() {"),
                        DiffLineView(REMOVED, 2, null, "    val timeout = 30"),
                        DiffLineView(REMOVED, 3, null, "    retry()"),
                        DiffLineView(ADDED, null, 2, "    val timeout = 60"),
                        DiffLineView(CONTEXT, 4, 3, "    connect()"),
                        DiffLineView(ADDED, null, 4, "    log()"),
                        DiffLineView(CONTEXT, 5, 5, "}"),
                    ),
                ),
                DiffHunkView("@@ -20,2 +20,1 @@", listOf(DiffLineView(REMOVED, 20, null, "// old"))),
            ),
        )

    @Test
    fun `unified rows keep git's order and pair removed with added lines for word highlights`() {
        val rows = diffRows(document, DiffMode.UNIFIED)
        assertEquals(10, rows.size)
        assertTrue(rows[0] is DiffRow.Header)
        val removed = rows[2] as DiffRow.Unified
        val added = rows[4] as DiffRow.Unified
        assertEquals("    val timeout = 30", removed.line.text)
        assertEquals(listOf(18..19), removed.words)
        assertEquals(listOf(18..19), added.words)
        assertEquals(
            emptyList(),
            (rows[3] as DiffRow.Unified).words,
            "the unpaired removed line has no word highlights",
        )
        assertEquals(
            listOf(0, 0, 0, 1, 2),
            rows.filterIsInstance<DiffRow.Unified>().filter {
                it.change >= 0
            }.map { it.change },
        )
    }

    @Test
    fun `side-by-side rows put pairs on one row and face extra lines with an empty cell`() {
        val rows = diffRows(document, DiffMode.SIDE_BY_SIDE).filterIsInstance<DiffRow.Split>()
        val first = rows[1]
        assertEquals("    val timeout = 30", first.left?.text)
        assertEquals("    val timeout = 60", first.right?.text)
        val extra = rows[2]
        assertEquals("    retry()", extra.left?.text)
        assertEquals(null, extra.right)
        val log = rows[4]
        assertEquals(null, log.left)
        assertEquals("    log()", log.right?.text)
        assertEquals(rows[0].left, rows[0].right, "a context line is on both sides")
    }

    @Test
    fun `F7 stops are the first row of each change block`() {
        val unified = diffRows(document, DiffMode.UNIFIED)
        assertEquals(listOf(2, 6, 9), changeStarts(unified))
        val split = diffRows(document, DiffMode.SIDE_BY_SIDE)
        assertEquals(listOf(2, 5, 8), changeStarts(split))
    }

    @Test
    fun `spans of a joined text split back into line-relative spans`() {
        val lines = listOf("/* a", "b */ x", "y")
        val spans = listOf(SyntaxSpan(0, 9, SyntaxKind.COMMENT), SyntaxSpan(12, 13, SyntaxKind.KEYWORD))
        val perLine = splitSpans(lines, spans)
        assertEquals(listOf(SyntaxSpan(0, 4, SyntaxKind.COMMENT)), perLine[0])
        assertEquals(listOf(SyntaxSpan(0, 4, SyntaxKind.COMMENT)), perLine[1])
        assertEquals(listOf(SyntaxSpan(0, 1, SyntaxKind.KEYWORD)), perLine[2])
    }

    @Test
    fun `the default highlighter colors Kotlin by extension and leaves unknown files plain`() {
        val code = "val name = \"graft\" // note"
        val spans = Highlighter.Default.highlight(code, "src/Main.kt")
        fun kindAt(text: String) = spans.filter { code.indexOf(text) in it.start until it.end }.map { it.kind }
        assertTrue(SyntaxKind.KEYWORD in kindAt("val"), "$spans")
        assertTrue(SyntaxKind.STRING in kindAt("\"graft\""), "$spans")
        assertTrue(SyntaxKind.COMMENT in kindAt("// note"), "$spans")
        assertTrue(spans.all { it.end <= code.length })
        assertEquals(emptyList(), Highlighter.Default.highlight(code, "notes.unknown"))
        assertEquals(emptyList(), Highlighter.Default.highlight(code, "Makefile"))
    }
}
