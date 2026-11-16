// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.srctool.camouflage.core.text.CamoText
import com.srctool.graft.core.ui.theme.GraftIcon
import com.srctool.graft.core.ui.theme.GraftTheme
import com.srctool.graft.core.ui.theme.GraftThemeMode
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Keyboard behavior (focus order, typing, tab switching) and a screenshot per component and state. */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
class ComponentUiTest {
    private val tabs =
        listOf(
            DocumentTab("home", "Home", GraftIcon.HOME, pinned = true),
            DocumentTab("app", "app", GraftIcon.FOLDER),
            DocumentTab("api", "api", GraftIcon.FOLDER, modified = true),
        )

    private fun ui(width: Int = 480, height: Int = 240, block: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width, height) { block() }

    private fun ComposeUiTest.themed(mode: GraftThemeMode = GraftThemeMode.LIGHT, content: @Composable () -> Unit) =
        setContent { GraftTheme(mode) { content() } }

    private fun ComposeUiTest.snapshot(name: String) = onRoot().captureRoboImage("src/test/snapshots/$name.png")

    @Test
    fun `tabs move focus with arrows, select with Enter and reorder by keyboard`() = ui {
        val selected = mutableStateOf("app")
        val moves = mutableStateListOf<Pair<Int, Int>>()
        themed {
            DocumentTabs(tabs, selected.value, { selected.value = it }, {}, { from, to -> moves.add(from to to) })
        }
        onNodeWithContentDescription("app").requestFocus()
        onNodeWithContentDescription("app").performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithContentDescription("api").assertIsFocused()
        onNodeWithContentDescription("api").performKeyInput { pressKey(Key.Enter) }
        assertEquals("api", selected.value)
        onNodeWithContentDescription("api").assertIsSelected()
        onNodeWithContentDescription("api").performKeyInput {
            withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionLeft) } }
        }
        assertEquals(listOf(2 to 1), moves.toList())
        // A pinned tab doesn't move, and nothing moves past it.
        onNodeWithContentDescription("app").requestFocus()
        onNodeWithContentDescription("app").performKeyInput {
            withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionLeft) } }
        }
        assertEquals(1, moves.size)
    }

    @Test
    fun `Tab moves from tab to tab, never onto a close button`() = ui {
        themed { DocumentTabs(tabs, "app", {}, {}, { _, _ -> }) }
        onNodeWithContentDescription("app").requestFocus()
        onNodeWithContentDescription("app").performKeyInput { pressKey(Key.Tab) }
        onNodeWithContentDescription("api").assertIsFocused()
    }

    @Test
    fun `the divider resizes by arrows and collapses with Enter`() = ui {
        val state = SplitPaneState(200.dp, minSize = 100.dp)
        themed {
            SplitPane(state, Modifier.fillMaxSize(), first = { CamoText("left") }, second = { CamoText("right") })
        }
        val divider = onNodeWithContentDescription("Resize")
        divider.requestFocus()
        divider.performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(216.dp, state.size)
        divider.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionLeft) } }
        assertEquals(152.dp, state.size)
        divider.performKeyInput { pressKey(Key.Enter) }
        assertTrue(state.collapsed)
        onNodeWithText("left").assertDoesNotExist()
    }

    @Test
    fun `the palette moves through results, jumps groups and picks with Enter`() = ui(width = 600, height = 360) {
        val picked = mutableStateListOf<String>()
        var dismissed = false
        themed {
            CommandPalettePanel("", {
            }, paletteGroups(), { picked.add(it.id) }, { dismissed = true }, "Search everywhere")
        }
        val field = onNodeWithContentDescription("Search everywhere")
        field.assertIsFocused()
        field.performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithText("feature/login").assertIsSelected()
        field.performKeyInput { pressKey(Key.Tab) }
        onNodeWithText("Fix login timeout").assertIsSelected()
        field.performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("c1"), picked.toList())
        field.performKeyInput { pressKey(Key.Escape) }
        assertTrue(dismissed)
    }

    @Test
    fun `Esc in a tool window returns focus to the center`() = ui(width = 600, height = 360) {
        val branches =
            ToolWindow("branches", "Branches", GraftIcon.BRANCH, DockSide.LEFT) {
                Box(Modifier.size(40.dp).testTag("panel").focusable())
            }
        val dock = DockLayoutState().apply { show("branches", DockSide.LEFT) }
        themed { DockLayout(dock, listOf(branches)) { Box(Modifier.size(40.dp).testTag("center").focusable()) } }
        onNodeWithTag("panel").requestFocus()
        onNodeWithTag("panel").performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag("center").assertIsFocused()
    }

    @Test
    fun `speed search filters a list as it's typed into and Esc clears it`() = ui {
        val search = SpeedSearchState()
        val labels = listOf("main", "feature/login", "feature/logout", "release/1.4")
        themed {
            Column(Modifier.testTag("list").speedSearch(search).focusable()) {
                val shown = labels.filter { search.matches(it) != null }
                SpeedSearchField(search, matchCount = shown.size)
                shown.forEach { CamoText(it) }
            }
        }
        search.type("flog")
        waitForIdle()
        onNodeWithText("main").assertDoesNotExist()
        onNodeWithText("feature/login").assertExists()
        onNodeWithText("flog").assertExists()
        onNodeWithTag("list").requestFocus()
        onNodeWithTag("list").performKeyInput { pressKey(Key.Escape) }
        onNodeWithText("main").assertExists()
        assertEquals("", search.query)
    }

    // Screenshots: one per component and state, in Graft Light; the dock in Graft Dark too.

    @Test
    fun `screenshot, tabs`() = ui(height = 40) {
        themed { DocumentTabs(tabs, "app", {}, {}, { _, _ -> }) }
        snapshot("tabs")
    }

    @Test
    fun `screenshot, split pane with the divider focused`() = ui(height = 120) {
        val state = SplitPaneState(160.dp)
        themed {
            SplitPane(state, Modifier.fillMaxSize(), first = { CamoText("first") }, second = { CamoText("second") })
        }
        onNodeWithContentDescription("Resize").requestFocus()
        snapshot("split-pane-focused")
    }

    // One test per theme, so a missing or changed golden in one doesn't stop the other from rendering.
    @Test
    fun `screenshot, dock layout, light`() = dockScreenshot(GraftThemeMode.LIGHT)

    @Test
    fun `screenshot, dock layout, dark`() = dockScreenshot(GraftThemeMode.DARK)

    private fun dockScreenshot(mode: GraftThemeMode) = ui(width = 640, height = 360) {
        val left = ToolWindow("branches", "Branches", GraftIcon.BRANCH, DockSide.LEFT) { CamoText("main") }
        val bottom = ToolWindow("log", "Git log", GraftIcon.LIST, DockSide.BOTTOM) { CamoText("git log --graph") }
        val right = ToolWindow("details", "Details", GraftIcon.INFORMATION, DockSide.RIGHT) { CamoText("commit") }
        val dock =
            DockLayoutState().apply {
                show("branches", DockSide.LEFT)
                show("log", DockSide.BOTTOM)
            }
        themed(mode) { DockLayout(dock, listOf(left, bottom, right)) { CamoText("graph") } }
        snapshot("dock-layout-${mode.name.lowercase()}")
    }

    @Test
    fun `screenshot, table header`() = ui(height = 40) {
        val columns =
            TableColumnsState(
                listOf(
                    TableColumn("refs", "Branch / tag", 120.dp),
                    TableColumn("graph", "Graph", 80.dp, hideable = false),
                    TableColumn("message", "Message", 200.dp, fill = true, hideable = false),
                    TableColumn("author", "Author", 100.dp),
                ),
            )
        themed { TableHeader(columns) }
        snapshot("table-header")
    }

    @Test
    fun `screenshot, speed search`() = ui(height = 140) {
        val search = SpeedSearchState().apply { type("flog") }
        themed {
            Column(Modifier.width(240.dp)) {
                SpeedSearchField(search, matchCount = 2)
                listOf("feature/login", "feature/logout").forEach { label ->
                    Box(Modifier.height(24.dp)) {
                        androidx.compose.foundation.text.BasicText(highlightMatches(label, search.matches(label)))
                    }
                }
            }
        }
        snapshot("speed-search")
    }

    @Test
    fun `screenshot, command palette`() = ui(width = 600, height = 260) {
        themed { CommandPalettePanel("log", {}, paletteGroups(loading = true), {}, {}, "Search everywhere") }
        snapshot("command-palette")
    }

    private fun paletteGroups(loading: Boolean = false) = listOf(
        PaletteGroup(
            "branches",
            "Branches",
            listOf(
                PaletteItem("b1", "main", icon = GraftIcon.BRANCH),
                PaletteItem("b2", "feature/login", icon = GraftIcon.BRANCH, matches = listOf(8..10)),
            ),
        ),
        PaletteGroup(
            "commits",
            "Commits",
            listOf(
                PaletteItem(
                    "c1",
                    "Fix login timeout",
                    detail = "a1b2c3d · Ann Lee",
                    icon = GraftIcon.COMMIT,
                    matches = listOf(4..6),
                ),
            ),
            loading = loading,
        ),
        PaletteGroup(
            "actions",
            "Actions",
            listOf(PaletteItem("a1", "Show Git log", icon = GraftIcon.LIST, shortcut = "⌘3")),
        ),
    )
}
