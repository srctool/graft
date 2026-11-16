// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.components

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.dp
import com.srctool.graft.core.ui.theme.GraftIcon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComponentStateTest {
    @Test
    fun `speed search matches prefixes, substrings and camel humps`() {
        assertEquals(listOf(0..3), SpeedSearchMatcher.match("main", "MAIN"))
        assertEquals(listOf(8..12), SpeedSearchMatcher.match("feature/login", "login"))
        assertEquals(listOf(0..0, 7..9), SpeedSearchMatcher.match("featureLogin", "fLog"))
        assertEquals(listOf(0..0, 8..10), SpeedSearchMatcher.match("feature/login", "flog"))
        assertEquals(listOf(0..0, 4..6), SpeedSearchMatcher.match("fix-log-rotation", "flog"))
        assertNull(SpeedSearchMatcher.match("release", "fl"))
        assertEquals(emptyList(), SpeedSearchMatcher.match("anything", ""))
    }

    @Test
    fun `a filtered tree keeps the ancestors of matches, and a match keeps its children`() {
        data class Node(val name: String, val children: List<Node> = emptyList())
        val tree =
            listOf(
                Node("origin", listOf(Node("main"), Node("feature", listOf(Node("login"), Node("logout"))))),
                Node("upstream", listOf(Node("release"))),
            )
        val filtered = filterTree(tree, { it.children }, { it.name }, "login")
        assertEquals(listOf("origin"), filtered.map { it.node.name })
        val feature = filtered.single().children.single()
        assertNull(feature.matches)
        assertEquals(listOf("login"), feature.children.map { it.node.name })
        val kept = filterTree(tree, { it.children }, { it.name }, "feature").single().children.single()
        assertEquals(listOf("login", "logout"), kept.children.map { it.node.name })
    }

    // Building a key event is internal API; tests run against the exact Compose version the build pins.
    @OptIn(InternalComposeUiApi::class)
    @Test
    fun `typed characters edit the query, everything else stays with the list`() {
        val state = SpeedSearchState()
        fun key(key: Key, char: Char? = null, ctrl: Boolean = false) =
            KeyEvent(key = key, type = KeyEventType.KeyDown, codePoint = char?.code ?: 0, isCtrlPressed = ctrl)
        assertFalse(state.onKey(key(Key.Spacebar, ' ')), "a space alone stays the list's")
        assertTrue(state.onKey(key(Key.F, 'f')))
        assertTrue(state.onKey(key(Key.L, 'l')))
        assertEquals("fl", state.query)
        assertFalse(state.onKey(key(Key.C, 'c', ctrl = true)), "a shortcut isn't typing")
        assertFalse(state.onKey(key(Key.DirectionDown)))
        assertTrue(state.onKey(key(Key.Backspace, '\b')))
        assertEquals("f", state.query)
        assertTrue(state.onKey(key(Key.Escape, '\u001b')))
        assertEquals("", state.query)
        assertFalse(state.onKey(key(Key.Escape, '\u001b')), "Esc with nothing typed is the screen's")
    }

    @Test
    fun `a split keeps its size within bounds, collapses and resets`() {
        val split = SplitPaneState(260.dp, minSize = 120.dp, maxSize = 400.dp)
        split.resizeBy(500.dp)
        assertEquals(400.dp, split.size)
        split.resize(10.dp)
        assertEquals(120.dp, split.size)
        split.toggleCollapsed()
        assertTrue(split.collapsed)
        split.reset()
        assertEquals(260.dp, split.size)
        assertFalse(split.collapsed)
    }

    @Test
    fun `columns resize above their minimum, hide unless they're the last, and round-trip`() {
        val columns =
            TableColumnsState(
                listOf(
                    TableColumn("graph", "Graph", 120.dp, minWidth = 60.dp, hideable = false),
                    TableColumn("message", "Message", 300.dp, fill = true, hideable = false),
                    TableColumn("author", "Author", 140.dp),
                    TableColumn("date", "Date", 120.dp),
                ),
            )
        columns.resizeBy("graph", (-500).dp)
        assertEquals(60.dp, columns.width("graph"))
        columns.resizeBy("message", 50.dp)
        assertEquals(300.dp, columns.width("message"), "the fill column takes the rest, it isn't sized")
        columns.setVisible("graph", false)
        assertTrue(columns.isVisible("graph"), "not hideable")
        columns.setVisible("author", false)
        assertEquals(listOf("graph", "message", "date"), columns.visibleColumns.map { it.id })

        val restored = TableColumnsState(columns.columns)
        restored.restore(columns.snapshot() + ("gone" to 10f))
        assertEquals(60.dp, restored.width("graph"))
        assertFalse(restored.isVisible("author"))
    }

    @Test
    fun `tool windows open one per dock, move between docks and round-trip`() {
        val branches = ToolWindow("branches", "Branches", GraftIcon.BRANCH, DockSide.LEFT) {}
        val tags = ToolWindow("tags", "Tags", GraftIcon.TAG, DockSide.LEFT) {}
        val log = ToolWindow("log", "Git log", GraftIcon.LIST, DockSide.BOTTOM) {}
        val dock = DockLayoutState()
        dock.toggle(branches)
        dock.toggle(tags)
        assertEquals("tags", dock.open(DockSide.LEFT), "one window per dock")
        assertFalse(dock.isOpen("branches"))

        dock.toggleSide(DockSide.LEFT, fallback = "branches")
        assertNull(dock.open(DockSide.LEFT))
        dock.toggleSide(DockSide.LEFT, fallback = "branches")
        assertEquals("tags", dock.open(DockSide.LEFT), "a dock reopens with what it showed last")

        dock.move(tags, DockSide.RIGHT)
        assertEquals(DockSide.RIGHT, dock.side("tags", DockSide.LEFT))
        assertEquals("tags", dock.open(DockSide.RIGHT), "it stays open where it went")
        dock.toggle(log)
        dock.hideAll()
        assertTrue(DockSide.entries.all { dock.open(it) == null })

        dock.show("log", DockSide.BOTTOM)
        val restored = DockLayoutState().apply { restore(dock.snapshot()) }
        assertEquals(DockSide.RIGHT, restored.side("tags", DockSide.LEFT))
        assertEquals("log", restored.open(DockSide.BOTTOM))
    }
}
