// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.text.CamoText
import com.srctool.graft.core.ui.git.DiffLineKind.ADDED
import com.srctool.graft.core.ui.git.DiffLineKind.CONTEXT
import com.srctool.graft.core.ui.git.DiffLineKind.REMOVED
import com.srctool.graft.core.ui.git.LaneEdgeKind.BOTTOM
import com.srctool.graft.core.ui.git.LaneEdgeKind.TOP
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.core.ui.theme.GraftTheme
import com.srctool.graft.core.ui.theme.GraftThemeMode
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test
import kotlin.test.assertEquals

/** Screen-reader labels and keys of the diff viewer, and screenshots of every git widget in both themes. */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
class GitWidgetUiTest {
    private fun ui(width: Int = 480, height: Int = 240, block: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width, height) { block() }

    private fun ComposeUiTest.themed(mode: GraftThemeMode = GraftThemeMode.LIGHT, content: @Composable () -> Unit) =
        setContent { GraftTheme(mode) { content() } }

    private fun ComposeUiTest.snapshot(name: String) = onRoot().captureRoboImage("src/test/snapshots/$name.png")

    private val document =
        DiffDocument(
            "src/main/kotlin/Login.kt",
            listOf(
                DiffHunkView(
                    "@@ -10,6 +10,7 @@ class Login",
                    listOf(
                        DiffLineView(CONTEXT, 10, 10, "    fun login(user: String) {"),
                        DiffLineView(REMOVED, 11, null, "        val timeout = 30 // seconds"),
                        DiffLineView(ADDED, null, 11, "        val timeout = 60 // seconds"),
                        DiffLineView(CONTEXT, 12, 12, "        connect(user, timeout)"),
                        DiffLineView(ADDED, null, 13, "        log(\"login: \$user\")"),
                        DiffLineView(CONTEXT, 13, 14, "    }"),
                        DiffLineView(REMOVED, 14, null, "    @Deprecated(\"old\")"),
                        DiffLineView(CONTEXT, 15, 15, "    fun logout() = Unit"),
                    ),
                ),
                DiffHunkView(
                    "@@ -40,2 +41,2 @@ class Login",
                    listOf(
                        DiffLineView(REMOVED, 40, null, "    private val retries = 3"),
                        DiffLineView(ADDED, null, 41, "    private val retries = 5"),
                        DiffLineView(CONTEXT, 41, 42, "}"),
                    ),
                ),
            ),
        )

    @Test
    fun `every diff line is read with its change and its number on its side`() = ui(width = 640) {
        themed { DiffViewer(document, Modifier.fillMaxSize()) }
        fun line(n: Int) = document.hunks[0].lines[n].text
        onNodeWithContentDescription("Added line 13: ${line(4)}").assertExists()
        onNodeWithContentDescription("Removed line 11: ${line(1)}").assertExists()
        onNodeWithContentDescription("Line 12: ${line(3)}").assertExists()
        onNodeWithContentDescription("@@ -10,6 +10,7 @@ class Login").assertExists()
    }

    @Test
    fun `side by side, each cell is read on its own side`() = ui(width = 800) {
        themed { DiffViewer(document, Modifier.fillMaxSize(), mode = DiffMode.SIDE_BY_SIDE) }
        val lines = document.hunks[0].lines
        onNodeWithContentDescription("Removed line 11: ${lines[1].text}").assertExists()
        onNodeWithContentDescription("Added line 11: ${lines[2].text}").assertExists()
    }

    @Test
    fun `F7 and Shift+F7 move between changes`() = ui(width = 640) {
        val state = DiffViewerState()
        themed { DiffViewer(document, Modifier.fillMaxSize().testTag("diff"), state = state) }
        val diff = onNodeWithTag("diff")
        diff.requestFocus()
        diff.performKeyInput { pressKey(Key.F7) }
        waitForIdle()
        assertEquals(0, state.currentChange)
        diff.performKeyInput {
            pressKey(Key.F7)
            pressKey(Key.F7)
            pressKey(Key.F7)
        }
        waitForIdle()
        assertEquals(3, state.currentChange)
        assertEquals(4, state.changeCount)
        diff.performKeyInput { pressKey(Key.F7) }
        waitForIdle()
        assertEquals(3, state.currentChange, "it stays on the last change")
        diff.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.F7) } }
        waitForIdle()
        assertEquals(2, state.currentChange)
    }

    // Screenshots, in both themes for the graph, the refs and the unified diff.

    @Test
    fun `screenshot, graph, light`() = graphScreenshot(GraftThemeMode.LIGHT)

    @Test
    fun `screenshot, graph, dark`() = graphScreenshot(GraftThemeMode.DARK)

    @Test
    fun `screenshot, refs and marks, light`() = refsScreenshot(GraftThemeMode.LIGHT)

    @Test
    fun `screenshot, refs and marks, dark`() = refsScreenshot(GraftThemeMode.DARK)

    @Test
    fun `screenshot, unified diff, light`() = diffScreenshot(GraftThemeMode.LIGHT, DiffMode.UNIFIED)

    @Test
    fun `screenshot, unified diff, dark`() = diffScreenshot(GraftThemeMode.DARK, DiffMode.UNIFIED)

    @Test
    fun `screenshot, side-by-side diff, light`() = diffScreenshot(GraftThemeMode.LIGHT, DiffMode.SIDE_BY_SIDE)

    private fun graphScreenshot(mode: GraftThemeMode) = ui(width = 420, height = 9 * 26) {
        themed(mode) {
            Column(Modifier.fillMaxSize().background(Camouflage.colors.surface)) {
                graphRows.forEach { (shape, label) ->
                    Row(
                        Modifier.height(Graft.tokens.density.graphRow),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GraphCanvas(shape, Modifier.width(56.dp).height(Graft.tokens.density.graphRow))
                        CamoText(label, style = Graft.tokens.dense)
                    }
                }
            }
        }
        snapshot("graph-${mode.name.lowercase()}")
    }

    private fun refsScreenshot(mode: GraftThemeMode) = ui(width = 480, height = 120) {
        themed(mode) {
            val lanes = Graft.tokens.lanes
            Column(
                Modifier.fillMaxSize().background(Camouflage.colors.surface).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RefChip(
                        RefLabel(
                            "main",
                            RefLabelKind.LOCAL,
                            head = true,
                            mergedRemote = "origin/main",
                            ahead = 2,
                            behind = 3,
                        ),
                        lanes[0],
                    )
                    RefChip(RefLabel("feature/login", RefLabelKind.LOCAL), lanes[1])
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RefChip(RefLabel("origin/release", RefLabelKind.REMOTE), lanes[2])
                    RefChip(RefLabel("v1.4.0", RefLabelKind.TAG), lanes[4])
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CheckState.entries.forEach { StatusDot(it, it.name) }
                    AuthorInitials("Dwiki Riyadi", "dwiki@example.com")
                    AuthorInitials("Ann Lee", "ann@example.com")
                    AuthorInitials("dependabot[bot]", "bot@example.com", bot = true)
                }
            }
        }
        snapshot("refs-${mode.name.lowercase()}")
    }

    private fun diffScreenshot(mode: GraftThemeMode, diffMode: DiffMode) =
        ui(width = if (diffMode == DiffMode.UNIFIED) 520 else 820, height = 280) {
            themed(mode) { DiffViewer(document, Modifier.fillMaxSize(), mode = diffMode) }
            waitForIdle()
            snapshot("diff-${diffMode.name.lowercase().replace('_', '-')}-${mode.name.lowercase()}")
        }

    /** A small graph with every shape: WIP, a fork and its merge, a stash, a shelf, lines passing by. */
    private val graphRows: List<Pair<SimpleGraphRow, String>> =
        listOf(
            SimpleGraphRow(0, 0, listOf(LaneEdge(BOTTOM, 0, 0, 0)), marker = GraphMarker.WIP) to "Uncommitted changes",
            SimpleGraphRow(0, 0, listOf(LaneEdge(TOP, 0, 0, 0), LaneEdge(BOTTOM, 0, 0, 0))) to "Fix login timeout",
            SimpleGraphRow(
                0,
                0,
                listOf(LaneEdge(TOP, 0, 0, 0), LaneEdge(BOTTOM, 0, 0, 0), LaneEdge(BOTTOM, 0, 1, 1)),
            ) to "Merge feature/login",
            SimpleGraphRow(1, 1, listOf(LaneEdge(TOP, 1, 1, 1), LaneEdge(BOTTOM, 1, 1, 1)), passing = mapOf(0 to 0)) to
                "Add retry",
            SimpleGraphRow(2, 2, listOf(LaneEdge(BOTTOM, 2, 2, 2)), mapOf(0 to 0, 1 to 1), GraphMarker.STASH) to
                "WIP on main",
            SimpleGraphRow(
                0,
                0,
                listOf(LaneEdge(TOP, 0, 0, 0), LaneEdge(TOP, 1, 0, 1), LaneEdge(BOTTOM, 0, 0, 0)),
                mapOf(2 to 2),
            ) to
                "Update deps",
            SimpleGraphRow(1, 3, listOf(LaneEdge(BOTTOM, 1, 1, 3)), mapOf(0 to 0, 2 to 2), GraphMarker.SHELF) to
                "Shelf: experiment",
            SimpleGraphRow(
                0,
                0,
                listOf(
                    LaneEdge(TOP, 0, 0, 0),
                    LaneEdge(TOP, 1, 0, 3),
                    LaneEdge(TOP, 2, 0, 2),
                    LaneEdge(BOTTOM, 0, 0, 0),
                ),
            ) to "Release 1.4",
            SimpleGraphRow(0, 0, listOf(LaneEdge(TOP, 0, 0, 0))) to "Initial commit",
        )
}
