// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.git

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.srctool.graft.core.ui.git.DiffLineKind.ADDED
import com.srctool.graft.core.ui.git.DiffLineKind.CONTEXT
import com.srctool.graft.core.ui.git.DiffLineKind.REMOVED
import com.srctool.graft.core.ui.theme.GraftTheme
import com.srctool.graft.core.ui.theme.GraftThemeMode
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test
import kotlin.test.assertEquals

/** The diff's hunk and line checkboxes: what they report, how they read, and that the keys stay in step with them. */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
class DiffSelectionUiTest {
    private val document =
        DiffDocument(
            "src/Login.kt",
            listOf(
                DiffHunkView(
                    "@@ -10,4 +10,5 @@ class Login",
                    listOf(
                        DiffLineView(CONTEXT, 10, 10, "fun login() {"),
                        DiffLineView(REMOVED, 11, null, "    val timeout = 30"),
                        DiffLineView(ADDED, null, 11, "    val timeout = 60"),
                        DiffLineView(ADDED, null, 12, "    log(\"login\")"),
                        DiffLineView(CONTEXT, 12, 13, "}"),
                    ),
                ),
                DiffHunkView(
                    "@@ -40,2 +41,2 @@ class Login",
                    listOf(
                        DiffLineView(REMOVED, 40, null, "    val retries = 3"),
                        DiffLineView(ADDED, null, 41, "    val retries = 5"),
                    ),
                ),
            ),
        )

    private fun ui(mode: DiffMode = DiffMode.UNIFIED, block: ComposeUiTest.(Selected) -> Unit) =
        runDesktopComposeUiTest(640, 280) {
            val selected = Selected()
            setContent {
                GraftTheme(GraftThemeMode.LIGHT) { Viewer(selected, mode) }
            }
            block(selected)
        }

    private class Selected {
        var set by mutableStateOf(emptySet<DiffLineRef>())
        val state = DiffViewerState()
    }

    @Composable
    private fun Viewer(selected: Selected, mode: DiffMode) {
        DiffViewer(
            document,
            Modifier.fillMaxSize().testTag("diff"),
            mode = mode,
            state = selected.state,
            selection =
            DiffSelection(
                selected.set,
                onToggleHunk = { selected.set = document.toggleHunk(it, selected.set) },
                onToggleLine = { selected.set = toggleLine(it, selected.set) },
            ),
        )
    }

    private fun on(state: ToggleableState) = SemanticsMatcher.expectValue(
        androidx.compose.ui.semantics.SemanticsProperties.ToggleableState,
        state,
    )

    @Test
    fun `a line reports its tick and reads as on or off`() = ui { selected ->
        val line = onNodeWithContentDescription("Added line 11: ${document.hunks[0].lines[2].text}")
        line.assert(on(ToggleableState.Off))
        line.performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertEquals(setOf(DiffLineRef(0, 2)), selected.set)
        line.assert(on(ToggleableState.On))
    }

    @Test
    fun `context lines have no checkbox`() = ui {
        val context = onNodeWithContentDescription("Line 10: ${document.hunks[0].lines[0].text}")
        context.assert(
            SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.ToggleableState),
        )
    }

    @Test
    fun `a hunk header ticks its lines and reads as partly selected while some are`() = ui { selected ->
        val header = onNodeWithContentDescription("@@ -10,4 +10,5 @@ class Login")
        header.assert(on(ToggleableState.Off))
        selected.set = setOf(DiffLineRef(0, 1))
        waitForIdle()
        header.assert(on(ToggleableState.Indeterminate))
        header.performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertEquals(document.changedLines(0).toSet(), selected.set)
        header.assert(on(ToggleableState.On))
        header.performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertEquals(emptySet(), selected.set)
    }

    @Test
    fun `Down and Space walk the checkboxes and toggle what the cursor is on`() = ui { selected ->
        val diff = onNodeWithTag("diff")
        diff.requestFocus()
        diff.performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        assertEquals(0, selected.state.cursor, "the first hunk header")
        diff.performKeyInput { pressKey(Key.Spacebar) }
        waitForIdle()
        assertEquals(document.changedLines(0).toSet(), selected.set)
        diff.performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.Spacebar)
        }
        waitForIdle()
        assertEquals(setOf(DiffLineRef(0, 2), DiffLineRef(0, 3)), selected.set, "the removed line is off again")
        diff.performKeyInput {
            repeat(2) { pressKey(Key.DirectionDown) }
            pressKey(Key.DirectionUp)
        }
        waitForIdle()
        assertEquals(DiffRowKind.LINE, kindAt(selected.state), "context lines are skipped")
        diff.performKeyInput { repeat(10) { pressKey(Key.DirectionDown) } }
        waitForIdle()
        val last = selected.state.cursor
        diff.performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        assertEquals(last, selected.state.cursor, "it stays on the last checkbox")
    }

    private enum class DiffRowKind { HEADER, LINE }

    private fun kindAt(state: DiffViewerState) =
        if (state.rows[state.cursor] is DiffRow.Header) DiffRowKind.HEADER else DiffRowKind.LINE

    @Test
    fun `side by side, each cell has its own tick`() = ui(DiffMode.SIDE_BY_SIDE) { selected ->
        onNodeWithContentDescription("Removed line 11: ${document.hunks[0].lines[1].text}")
            .performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertEquals(setOf(DiffLineRef(0, 1)), selected.set)
        onNodeWithContentDescription(
            "Added line 11: ${document.hunks[0].lines[2].text}",
        ).assert(on(ToggleableState.Off))
    }

    @Test
    fun `without a selection the viewer has no checkboxes and ignores Space`() = runDesktopComposeUiTest(640, 280) {
        setContent { GraftTheme(GraftThemeMode.LIGHT) { DiffViewer(document, Modifier.fillMaxSize().testTag("diff")) } }
        onNodeWithContentDescription("Added line 11: ${document.hunks[0].lines[2].text}")
            .assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.ToggleableState))
    }

    @Test
    fun `with hunk ticks only, lines have none and each header runs its action`() {
        val restored = ArrayList<Int>()
        runDesktopComposeUiTest(640, 280) {
            val selected = Selected()
            setContent {
                GraftTheme(GraftThemeMode.LIGHT) {
                    DiffViewer(
                        document,
                        Modifier.fillMaxSize().testTag("diff"),
                        state = selected.state,
                        selection = DiffSelection(
                            selected.set,
                            onToggleHunk = { selected.set = document.toggleHunk(it, selected.set) },
                            onToggleLine = { selected.set = toggleLine(it, selected.set) },
                            lines = false,
                            hunkAction = HunkAction("Restore hunk") { restored += it },
                        ),
                    )
                }
            }
            onNodeWithContentDescription("Added line 11: ${document.hunks[0].lines[2].text}")
                .assert(
                    SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.ToggleableState),
                )
            val diff = onNodeWithTag("diff")
            diff.requestFocus()
            diff.performKeyInput {
                pressKey(Key.DirectionDown)
                pressKey(Key.DirectionDown)
                pressKey(Key.Spacebar)
            }
            waitForIdle()
            assertEquals(document.changedLines(1).toSet(), selected.set, "the cursor goes from header to header")
            onAllNodesWithText("Restore hunk")[1].performClick()
            assertEquals(listOf(1), restored)
        }
    }

    @Test
    fun `screenshot, selection, light`() = shot(GraftThemeMode.LIGHT)

    @Test
    fun `screenshot, selection, dark`() = shot(GraftThemeMode.DARK)

    private fun shot(mode: GraftThemeMode) = runDesktopComposeUiTest(640, 280) {
        val selected = Selected().apply { set = setOf(DiffLineRef(0, 2), DiffLineRef(0, 3), DiffLineRef(1, 0)) }
        setContent { GraftTheme(mode) { Viewer(selected, DiffMode.UNIFIED) } }
        onNodeWithTag("diff").requestFocus()
        onNodeWithTag("diff").performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onRoot().captureRoboImage("src/test/snapshots/diff-selection-${mode.name.lowercase()}.png")
    }
}
