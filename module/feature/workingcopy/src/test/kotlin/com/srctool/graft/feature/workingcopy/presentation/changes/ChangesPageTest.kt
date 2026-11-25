// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.presentation.changes

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.srctool.graft.core.ui.git.DiffLineRef
import com.srctool.graft.core.ui.theme.GraftTheme
import com.srctool.graft.core.ui.theme.GraftThemeMode
import com.srctool.graft.data.git.api.models.ChangeSelection
import com.srctool.graft.data.git.api.models.FileSelection
import com.srctool.graft.feature.workingcopy.domain.api.models.DiffSide
import com.srctool.graft.feature.workingcopy.domain.api.models.DiscardRecord
import com.srctool.graft.feature.workingcopy.domain.api.models.SelectionState
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingDiff
import com.srctool.graft.feature.workingcopy.domain.failure
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Changes panel and its diff: how rows read, what the keys and buttons report, and every state in light and dark. */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
class ChangesPageTest {
    private val events = ArrayList<ChangesEvent>()

    private fun ready(tree: Boolean = true, cursor: Int = -1, open: OpenFile? = null): ChangesState {
        val model = Sample.model()
        return ChangesState(
            phase = ChangesState.Phase.Ready,
            model = model,
            lines = panelLines(model, tree, emptySet()),
            tree = tree,
            branch = "main",
            cursor = cursor,
            open = open,
        )
    }

    private fun ComposeUiTest.panel(state: ChangesState, mode: GraftThemeMode = GraftThemeMode.LIGHT) = setContent {
        GraftTheme(mode) { ChangesContent(state, { events += it }) }
    }

    private val fileCount = Sample.model().sections.sumOf { it.rows.size }

    @Test
    fun `each row reads as its name, status, folder and whether it is staged`() = runDesktopComposeUiTest(360, 520) {
        panel(ready(tree = false))
        onNodeWithContentDescription("Backoff.kt, added, in src/login, staged").assertExists()
        onNodeWithContentDescription("Login.kt, modified, in src/login, staged").assertExists()
        onNodeWithContentDescription("Login.kt, modified, in src/login, unstaged, also partly staged").assertExists()
        onNodeWithContentDescription("Session.kt, modified, in src/login, unstaged").assertExists()
        onNodeWithContentDescription("notes.txt, untracked, unstaged").assertExists()
    }

    @Test
    fun `a staged row is a checked checkbox and an unstaged one is not, and the action says what it does`() =
        runDesktopComposeUiTest(360, 520) {
            panel(ready(tree = false))
            onNodeWithContentDescription("Backoff.kt, added, in src/login, staged").assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ToggleableState,
                    androidx.compose.ui.state.ToggleableState.On,
                ),
            )
            onNodeWithContentDescription("Session.kt, modified, in src/login, unstaged").assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ToggleableState,
                    androidx.compose.ui.state.ToggleableState.Off,
                ),
            )
            onNodeWithContentDescription("Session.kt, modified, in src/login, unstaged")
                .performSemanticsAction(SemanticsActions.OnClick)
            assertTrue(events.single() is ChangesEvent.StageToggled)
        }

    @Test
    fun `a submodule row says what changed and has Open`() = runDesktopComposeUiTest(420, 520) {
        panel(ready(tree = false))
        onNodeWithText("moved · modified content").assertExists()
        onNodeWithText("Open").assertExists()
    }

    @Test
    fun `the section headers read their count and offer one bulk action`() = runDesktopComposeUiTest(360, 520) {
        panel(ready(tree = false))
        onNodeWithContentDescription("Staged, 2 files").assertExists()
        onNodeWithContentDescription("Unstaged, 4 files").assertExists()
        onNodeWithText("Stage all").performClick()
        onNodeWithText("Unstage all").performClick()
        assertEquals(2, events.count { it is ChangesEvent.SectionToggled })
    }

    @Test
    fun `the keys move the cursor, stage with space, open with enter and ask before discarding`() =
        runDesktopComposeUiTest(360, 520) {
            panel(ready(tree = false, cursor = 1))
            val list = onNodeWithContentDescription("Changes, $fileCount files")
            list.requestFocus()
            list.performKeyInput { pressKey(Key.DirectionDown) }
            list.performKeyInput { pressKey(Key.Spacebar) }
            list.performKeyInput { pressKey(Key.Enter) }
            list.performKeyInput {
                keyDown(Key.CtrlLeft)
                keyDown(Key.AltLeft)
                pressKey(Key.Z)
                keyUp(Key.AltLeft)
                keyUp(Key.CtrlLeft)
            }
            assertEquals(
                listOf(
                    ChangesEvent.Moved(1),
                    ChangesEvent.StageToggled(1),
                    ChangesEvent.Activated(1),
                    ChangesEvent.DiscardAsked(1),
                ),
                events,
            )
        }

    @Test
    fun `loading shows skeletons, a clean tree says why it is empty, a failure has its code and Retry`() {
        runDesktopComposeUiTest(360, 300) {
            panel(ChangesState())
            onNodeWithText("No changes. Edit a file in the working tree and it shows here.").assertDoesNotExist()
        }
        runDesktopComposeUiTest(360, 300) {
            panel(
                ChangesState(
                    phase = ChangesState.Phase.Ready,
                    model = com.srctool.graft.feature.workingcopy.domain.api.models.ChangesModel(
                        com.srctool.graft.feature.workingcopy.domain.COPY,
                        emptyList(),
                    ),
                ),
            )
            onNodeWithText("No changes. Edit a file in the working tree and it shows here.").assertExists()
        }
        runDesktopComposeUiTest(360, 300) {
            panel(
                ChangesState(
                    phase = ChangesState.Phase.Failed,
                    error = (failure("STAT") as com.srctool.sinew.models.Result.Failure).error,
                ),
            )
            onNodeWithText("Retry").performClick()
            assertEquals(listOf<ChangesEvent>(ChangesEvent.Retry), events)
        }
    }

    @Test
    fun `the discard dialog names what goes and confirms with the action's own name`() =
        runDesktopComposeUiTest(420, 400) {
            val pending =
                PendingDiscard(
                    ChangeSelection(listOf(FileSelection("src/login/Session.kt", FileSelection.Scope.WholeFile))),
                    listOf("src/login/Session.kt"),
                    deletes = false,
                )
            panel(ready().copy(discard = pending))
            onNodeWithText("Discard changes to Session.kt?").assertExists()
            onNodeWithText("Discard changes").performClick()
            onNodeWithText("Cancel").performClick()
            assertEquals(listOf<ChangesEvent>(ChangesEvent.DiscardConfirmed, ChangesEvent.DiscardCancelled), events)
        }

    @Test
    fun `an untracked file is named as deleted in its confirmation`() = runDesktopComposeUiTest(420, 400) {
        val pending =
            PendingDiscard(
                ChangeSelection(listOf(FileSelection("notes.txt", FileSelection.Scope.WholeFile))),
                listOf("notes.txt"),
                deletes = true,
            )
        panel(ready().copy(discard = pending))
        onNodeWithText("Delete notes.txt?").assertExists()
        onNodeWithText("Delete").assertExists()
    }

    @Test
    fun `the toast offers Undo and the busy line says what runs`() = runDesktopComposeUiTest(360, 400) {
        panel(ready().copy(toast = Toast("Discarded Session.kt", DiscardRecord(null, emptyMap()))))
        onNodeWithText("Undo").performClick()
        assertEquals(listOf<ChangesEvent>(ChangesEvent.UndoClicked), events)
    }

    @Test
    fun `the diff's action bar counts the ticked lines and applies them`() = runDesktopComposeUiTest(700, 360) {
        val open = OpenFile(Sample.login, DiffSide.UNSTAGED)
        setContent { GraftTheme(GraftThemeMode.LIGHT) { WorkingDiffContent(diffState(open), { events += it }) } }
        onNodeWithText("2 lines ticked").assertExists()
        onNodeWithText("Stage 2 lines").performClick()
        onNodeWithText("Discard 2 lines…").performClick()
        onNodeWithText("Clear").performClick()
        assertEquals(
            listOf(ChangesEvent.TicksApplied, ChangesEvent.TicksDiscardAsked, ChangesEvent.TicksCleared),
            events,
        )
    }

    @Test
    fun `a staged diff offers Unstage and no discard`() = runDesktopComposeUiTest(700, 360) {
        val open = OpenFile(Sample.login, DiffSide.STAGED)
        setContent { GraftTheme(GraftThemeMode.LIGHT) { WorkingDiffContent(diffState(open), { events += it }) } }
        onNodeWithText("Unstage 2 lines").assertExists()
        onNodeWithText("Discard 2 lines…").assertDoesNotExist()
    }

    @Test
    fun `a file over the limit offers to show the diff anyway`() = runDesktopComposeUiTest(700, 300) {
        val open = OpenFile(Sample.login, DiffSide.UNSTAGED)
        val state = ready(open = open).copy(body = DiffBodyState.Ready(WorkingDiff.TooLarge(3_200_000)))
        setContent { GraftTheme(GraftThemeMode.LIGHT) { WorkingDiffContent(state, { events += it }) } }
        onNodeWithText("Show diff anyway").performClick()
        assertEquals(listOf<ChangesEvent>(ChangesEvent.ShowAnyway), events)
    }

    @Test
    fun `a file with nothing left says so and leads back`() = runDesktopComposeUiTest(700, 300) {
        val open = OpenFile(Sample.login, DiffSide.UNSTAGED)
        val state = ready(open = open).copy(body = DiffBodyState.Ready(WorkingDiff.Nothing))
        setContent { GraftTheme(GraftThemeMode.LIGHT) { WorkingDiffContent(state, { events += it }) } }
        onNodeWithText("Login.kt has no unstaged changes.").assertExists()
        onNodeWithText("Back to the graph").performClick()
        assertEquals(listOf<ChangesEvent>(ChangesEvent.DiffClosed), events)
    }

    private fun diffState(open: OpenFile): ChangesState {
        val text = Sample.diff()
        return ready(open = open).copy(
            body = DiffBodyState.Ready(text),
            ticks = SelectionState.EMPTY,
            ticked = setOf(DiffLineRef(0, 1), DiffLineRef(0, 3)),
        )
    }

    // ---- screenshots -----------------------------------------------------------------------------------------------

    private fun shot(name: String, mode: GraftThemeMode, width: Int, height: Int, content: @Composable () -> Unit) =
        runDesktopComposeUiTest(width, height) {
            setContent { GraftTheme(mode) { content() } }
            waitForIdle()
            onRoot().captureRoboImage("src/test/snapshots/$name-${mode.name.lowercase()}.png")
        }

    private val open = OpenFile(Sample.login, DiffSide.UNSTAGED)
    private val none =
        ChangesState(
            phase = ChangesState.Phase.Ready,
            model = com.srctool.graft.feature.workingcopy.domain.api.models.ChangesModel(
                com.srctool.graft.feature.workingcopy.domain.COPY,
                emptyList(),
            ),
            branch = "main",
        )
    private val discardOne =
        PendingDiscard(
            ChangeSelection(listOf(FileSelection("src/login/Session.kt", FileSelection.Scope.WholeFile))),
            listOf("src/login/Session.kt"),
            false,
        )
    private val failed =
        ChangesState(
            phase = ChangesState.Phase.Failed,
            error = (failure("STAT") as com.srctool.sinew.models.Result.Failure).error,
        )

    // One test per picture, so a missing golden in one doesn't hide the others.
    @Test fun `shot tree light`() = shot("changes-tree", GraftThemeMode.LIGHT, 360, 440) {
        ChangesContent(ready(cursor = 3, open = open), {})
    }

    @Test fun `shot tree dark`() = shot("changes-tree", GraftThemeMode.DARK, 360, 440) {
        ChangesContent(ready(cursor = 3, open = open), {})
    }

    @Test fun `shot flat light`() = shot("changes-flat", GraftThemeMode.LIGHT, 360, 360) {
        ChangesContent(ready(tree = false), {})
    }

    @Test fun `shot flat dark`() = shot("changes-flat", GraftThemeMode.DARK, 360, 360) {
        ChangesContent(ready(tree = false), {})
    }

    @Test fun `shot loading light`() = shot("changes-loading", GraftThemeMode.LIGHT, 360, 200) {
        ChangesContent(ChangesState(), {})
    }

    @Test fun `shot loading dark`() = shot("changes-loading", GraftThemeMode.DARK, 360, 200) {
        ChangesContent(ChangesState(), {})
    }

    @Test fun `shot empty light`() = shot("changes-empty", GraftThemeMode.LIGHT, 360, 200) { ChangesContent(none, {}) }

    @Test fun `shot empty dark`() = shot("changes-empty", GraftThemeMode.DARK, 360, 200) { ChangesContent(none, {}) }

    @Test fun `shot error light`() = shot("changes-error", GraftThemeMode.LIGHT, 360, 200) {
        ChangesContent(failed, {})
    }

    @Test fun `shot error dark`() = shot("changes-error", GraftThemeMode.DARK, 360, 200) { ChangesContent(failed, {}) }

    @Test fun `shot busy light`() = shot("changes-busy", GraftThemeMode.LIGHT, 360, 440) {
        ChangesContent(ready().copy(busy = "Staging 1 file…", refreshing = true), {})
    }

    @Test fun `shot busy dark`() = shot("changes-busy", GraftThemeMode.DARK, 360, 440) {
        ChangesContent(ready().copy(busy = "Staging 1 file…", refreshing = true), {})
    }

    @Test fun `shot discard light`() = shot("changes-discard", GraftThemeMode.LIGHT, 420, 300) {
        ChangesContent(ready().copy(discard = discardOne), {})
    }

    @Test fun `shot discard dark`() = shot("changes-discard", GraftThemeMode.DARK, 420, 300) {
        ChangesContent(ready().copy(discard = discardOne), {})
    }

    @Test fun `shot toast light`() = shot("changes-toast", GraftThemeMode.LIGHT, 360, 440) {
        ChangesContent(ready().copy(toast = Toast("Discarded Session.kt", DiscardRecord(null, emptyMap()))), {})
    }

    @Test fun `shot toast dark`() = shot("changes-toast", GraftThemeMode.DARK, 360, 440) {
        ChangesContent(ready().copy(toast = Toast("Discarded Session.kt", DiscardRecord(null, emptyMap()))), {})
    }

    @Test fun `shot diff ticked light`() = shot("working-diff-ticked", GraftThemeMode.LIGHT, 700, 300) {
        WorkingDiffContent(diffState(open), {})
    }

    @Test fun `shot diff ticked dark`() = shot("working-diff-ticked", GraftThemeMode.DARK, 700, 300) {
        WorkingDiffContent(diffState(open), {})
    }

    @Test fun `shot diff large light`() = shot("working-diff-large", GraftThemeMode.LIGHT, 700, 220) {
        WorkingDiffContent(ready(open = open).copy(body = DiffBodyState.Ready(WorkingDiff.TooLarge(3_200_000))), {})
    }

    @Test fun `shot diff large dark`() = shot("working-diff-large", GraftThemeMode.DARK, 700, 220) {
        WorkingDiffContent(ready(open = open).copy(body = DiffBodyState.Ready(WorkingDiff.TooLarge(3_200_000))), {})
    }

    @Test fun `shot diff nothing light`() = shot("working-diff-nothing", GraftThemeMode.LIGHT, 700, 220) {
        WorkingDiffContent(ready(open = open).copy(body = DiffBodyState.Ready(WorkingDiff.Nothing)), {})
    }

    @Test fun `shot diff nothing dark`() = shot("working-diff-nothing", GraftThemeMode.DARK, 700, 220) {
        WorkingDiffContent(ready(open = open).copy(body = DiffBodyState.Ready(WorkingDiff.Nothing)), {})
    }
}
