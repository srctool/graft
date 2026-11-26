// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.presentation.history

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.srctool.graft.core.ui.theme.GraftTheme
import com.srctool.graft.core.ui.theme.GraftThemeMode
import com.srctool.graft.data.git.api.models.SnapshotHit
import com.srctool.graft.feature.workingcopy.domain.api.models.RestoreRecord
import com.srctool.graft.feature.workingcopy.domain.failure
import io.github.takahirom.roborazzi.captureRoboImage
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val LOGIN = "src/login/Login.kt"

/** Local History: what it reads as, what its controls and keys report, and every state in light and dark. */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
class HistoryPageTest {
    private val events = ArrayList<HistoryEvent>()

    private val ready =
        HistoryState(
            phase = HistoryState.Phase.Ready,
            snapshots = HistorySample.snapshots,
            selected = HistorySample.newest.id,
            files = HistorySample.files,
            file = LOGIN,
            body = HistoryBody.Ready(HistorySample.diff()),
        )

    private fun ComposeUiTest.page(state: HistoryState, mode: GraftThemeMode = GraftThemeMode.LIGHT) = setContent {
        GraftTheme(mode) { HistoryContent(state, { events += it }, ZoneOffset.UTC, HistorySample.today) }
    }

    private fun test(state: HistoryState, block: ComposeUiTest.() -> Unit) = runDesktopComposeUiTest(900, 600) {
        page(state)
        block()
    }

    @Test
    fun `rows read as their time, day and label, and files as their path and status`() = test(ready) {
        onNodeWithContentDescription("14:02 Today, before discard").assertExists()
        onNodeWithContentDescription("13:57 Today, Automatic").assertExists()
        onNodeWithContentDescription("18:11 Yesterday, before refactor").assertExists()
        onNodeWithContentDescription("notes.txt, deleted").assertExists()
        onNodeWithContentDescription("Login.kt, 14:02 compared with now").assertExists()
    }

    @Test
    fun `the restore buttons report what they restore`() = test(ready.copy(ticks = setOf(0))) {
        onNodeWithText("Restore file").performClick()
        onNodeWithText("Restore all").performClick()
        onNodeWithText("Restore hunk").performClick()
        onNodeWithText("Restore 1 hunk").performClick()
        assertEquals(
            listOf(
                HistoryEvent.FileRestored,
                HistoryEvent.AllRestored,
                HistoryEvent.HunkRestored(0),
                HistoryEvent.TickedRestored,
            ),
            events,
        )
    }

    @Test
    fun `arrows move in the list that has focus`() = test(ready) {
        val list = onNodeWithContentDescription("Snapshots, 3")
        list.requestFocus()
        list.performKeyInput { pressKey(Key.DirectionDown) }
        val files = onNodeWithContentDescription("Files in this snapshot, 3")
        files.requestFocus()
        files.performKeyInput { pressKey(Key.DirectionUp) }
        assertEquals(listOf(HistoryEvent.SnapshotMoved(1), HistoryEvent.FileMoved(-1)), events)
    }

    @Test
    fun `Escape clears a search before it goes back`() {
        test(ready.copy(query = "login", hits = listOf(SnapshotHit(HistorySample.middle, listOf(LOGIN))))) {
            val list = onNodeWithContentDescription("Snapshots, 1")
            list.requestFocus()
            list.performKeyInput { pressKey(Key.Escape) }
            assertEquals(listOf<HistoryEvent>(HistoryEvent.SearchCleared), events)
        }
        events.clear()
        test(ready) {
            val list = onNodeWithContentDescription("Snapshots, 3")
            list.requestFocus()
            list.performKeyInput { pressKey(Key.Escape) }
            assertEquals(listOf<HistoryEvent>(HistoryEvent.Closed), events)
        }
    }

    @Test
    fun `Mod Alt Y asks for a snapshot`() = test(ready) {
        val list = onNodeWithContentDescription("Snapshots, 3")
        list.requestFocus()
        list.performKeyInput {
            keyDown(Key.CtrlLeft)
            keyDown(Key.AltLeft)
            pressKey(Key.Y)
            keyUp(Key.AltLeft)
            keyUp(Key.CtrlLeft)
        }
        assertEquals(listOf<HistoryEvent>(HistoryEvent.SnapshotAsked), events)
    }

    @Test
    fun `an empty history offers a snapshot`() = test(HistoryState(phase = HistoryState.Phase.Ready)) {
        onNodeWithText("No snapshots yet. Graft takes one shortly after files change.").assertExists()
    }

    @Test
    fun `a file no snapshot has says so`() = test(HistoryState(phase = HistoryState.Phase.Ready, path = LOGIN)) {
        onNodeWithText("No snapshot has this file.").assertExists()
    }

    @Test
    fun `the snapshot dialog takes a label`() = test(ready.copy(dialog = HistoryDialog.SnapshotNow)) {
        // The dialog's field is the last one composed, over the search field.
        val fields = onAllNodes(hasSetTextAction())
        fields[fields.fetchSemanticsNodes().lastIndex].performTextInput("before refactor")
        waitForIdle()
        onNodeWithText("Snapshot").performClick()
        assertTrue(HistoryEvent.SnapshotConfirmed("before refactor") in events)
    }

    // ---- screenshots, one test each: a missing golden stops its test at the first capture

    private fun shot(name: String, state: HistoryState) {
        val failures = listOf(GraftThemeMode.LIGHT, GraftThemeMode.DARK).map { mode ->
            runCatching {
                runDesktopComposeUiTest(900, 600) {
                    page(state, mode)
                    waitForIdle()
                    // Syntax colors are worked out off the main thread.
                    Thread.sleep(HIGHLIGHT_MS)
                    waitForIdle()
                    onAllNodes(isRoot()).let { roots ->
                        roots[roots.fetchSemanticsNodes().lastIndex]
                            .captureRoboImage("src/test/snapshots/$name-${mode.name.lowercase()}.png")
                    }
                }
            }
        }
        failures.firstOrNull { it.isFailure }?.getOrThrow()
    }

    @Test
    fun `screenshot, timeline`() = shot("history-timeline", ready.copy(ticks = setOf(0)))

    @Test
    fun `screenshot, one file`() = shot(
        "history-file",
        HistoryState(
            phase = HistoryState.Phase.Ready,
            path = LOGIN,
            versions = FakeLocalHistory().versions,
            selected = HistorySample.middle.id,
            file = LOGIN,
            body = HistoryBody.Ready(HistorySample.diff()),
        ),
    )

    @Test
    fun `screenshot, deleted files`() = shot(
        "history-deleted",
        HistoryState(
            phase = HistoryState.Phase.Ready,
            view = HistoryView.DELETED,
            deleted = FakeLocalHistory().deleted,
            selected = HistorySample.middle.id,
            file = "notes.txt",
            body = HistoryBody.Ready(HistorySample.diff("notes.txt")),
        ),
    )

    @Test
    fun `screenshot, search results`() = shot(
        "history-search",
        ready.copy(
            query = "login",
            hits = listOf(SnapshotHit(HistorySample.middle, listOf(LOGIN))),
            selected = HistorySample.middle.id,
        ),
    )

    @Test
    fun `screenshot, no matches`() = shot("history-no-matches", ready.copy(query = "zzz", hits = emptyList()))

    @Test
    fun `screenshot, empty`() = shot("history-empty", HistoryState(phase = HistoryState.Phase.Ready))

    @Test
    fun `screenshot, loading`() = shot("history-loading", HistoryState())

    @Test
    fun `screenshot, error`() =
        shot("history-error", HistoryState(phase = HistoryState.Phase.Failed, error = failure("LHST").error))

    @Test
    fun `screenshot, busy`() = shot("history-busy", ready.copy(busy = "Restoring…"))

    @Test
    fun `screenshot, done with Undo`() = shot(
        "history-done",
        ready.copy(
            toast = HistoryToast(
                "Restored Login.kt to 14:02.",
                RestoreRecord.Files(HistorySample.newest.id, emptyMap()),
            ),
        ),
    )

    @Test
    fun `screenshot, snapshot dialog`() =
        shot("history-snapshot-dialog", ready.copy(dialog = HistoryDialog.SnapshotNow))

    @Test
    fun `screenshot, remove dialog`() = shot(
        "history-purge-dialog",
        ready.copy(path = LOGIN, versions = FakeLocalHistory().versions, dialog = HistoryDialog.Purge(LOGIN)),
    )

    private companion object {
        const val HIGHLIGHT_MS = 400L
    }
}
