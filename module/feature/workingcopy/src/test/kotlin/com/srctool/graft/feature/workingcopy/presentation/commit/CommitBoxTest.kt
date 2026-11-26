// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.presentation.commit

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
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
import com.srctool.graft.data.git.api.models.CoAuthor
import com.srctool.graft.data.git.api.models.CommitMessageText
import com.srctool.graft.data.git.api.models.GuardRule
import com.srctool.graft.data.git.api.models.GuardSeverity
import com.srctool.graft.feature.workingcopy.domain.failure
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The commit box and the guard dialog: how they read, what they report, and every state in light and dark. */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
class CommitBoxTest {
    private val events = ArrayList<CommitEvent>()

    private val ready =
        CommitState(
            loaded = true,
            staged = 3,
            summary = "Fix login timeout",
            previous = CommitMessageText("Earlier", ""),
            previousId = "a1b2c3d",
        )
    private val block = finding("src/login/Config.kt", fingerprint = "b1")
    private val env =
        finding(".env", fingerprint = "b2", line = null, rule = GuardRule.SENSITIVE_FILE)
            .copy(label = ".env file", excerpt = ".env")
    private val large =
        finding("assets/intro.mp4", GuardSeverity.WARN, "w1", null, GuardRule.LARGE_FILE)
            .copy(label = "Large file, 12 MB", excerpt = "12 MB")

    private fun ComposeUiTest.box(state: CommitState, mode: GraftThemeMode = GraftThemeMode.LIGHT) = setContent {
        GraftTheme(mode) { CommitBox(state, { events += it }) }
    }

    @Test
    fun `the button names the action and counts the staged files`() = runDesktopComposeUiTest(360, 520) {
        box(ready)
        onNodeWithText("Commit 3 files").performClick()
        assertEquals(listOf<CommitEvent>(CommitEvent.Submitted), events)
    }

    @Test
    fun `an unavailable commit says why, to the eye and to a screen reader`() = runDesktopComposeUiTest(360, 520) {
        box(ready.copy(summary = ""))
        onNodeWithText("Write a summary").assertExists()
        onNodeWithContentDescription("Commit 3 files, unavailable: Write a summary").assertExists()
        onNodeWithText("Commit 3 files").performClick()
        assertTrue(events.isEmpty(), "a disabled button reports nothing")
    }

    @Test
    fun `nothing staged and conflicts have their own reasons`() {
        runDesktopComposeUiTest(360, 520) {
            box(ready.copy(staged = 0))
            onNodeWithText("Nothing is staged").assertExists()
        }
        runDesktopComposeUiTest(360, 520) {
            box(ready.copy(conflicts = 1))
            onNodeWithText("Resolve the conflicts first").assertExists()
        }
    }

    @Test
    fun `the summary reads its length and the counter warns over 50`() = runDesktopComposeUiTest(360, 520) {
        box(ready.copy(summary = "x".repeat(60)))
        onNodeWithText("60/50").assertExists()
        onNodeWithContentDescription("Commit summary, 60 of 50 characters").assertExists()
    }

    @Test
    fun `typing reports the text, and mod enter in the summary commits`() = runDesktopComposeUiTest(360, 520) {
        box(ready)
        // The summary is the first field in the box.
        val summary = onAllNodes(hasSetTextAction())[0]
        summary.performTextInput("!")
        waitForIdle()
        assertTrue(events.any { it is CommitEvent.SummaryChanged }, "typing: $events")
        summary.requestFocus()
        summary.performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.Enter)
            keyUp(Key.CtrlLeft)
        }
        assertTrue(CommitEvent.Submitted in events, "mod enter: $events")
    }

    @Test
    fun `a long description line is named`() = runDesktopComposeUiTest(360, 520) {
        box(ready.copy(description = "short\n" + "y".repeat(80)))
        onNodeWithText("Line 2 is over 72 characters").assertExists()
    }

    @Test
    fun `amend reads its commit and is unavailable before the first one`() {
        runDesktopComposeUiTest(360, 520) {
            box(ready)
            onNodeWithContentDescription("Amend previous commit a1b2c3d").assertExists()
        }
        runDesktopComposeUiTest(360, 520) {
            box(ready.copy(previous = null))
            onNodeWithContentDescription("Amend previous commit").assertExists()
        }
    }

    @Test
    fun `in amend mode the button says amend`() = runDesktopComposeUiTest(360, 520) {
        box(ready.copy(amend = true))
        onNodeWithText("Amend commit").assertExists()
    }

    @Test
    fun `options open to sign off, skip hooks, co-authors and empty commits`() = runDesktopComposeUiTest(360, 700) {
        val ann = CoAuthor("Ann Lee", "ann@example.invalid")
        box(ready.copy(optionsOpen = true, noVerify = true, recent = listOf(ann)))
        onNodeWithText("Sign off").assertExists()
        onNodeWithText("Skip hooks (--no-verify)").assertExists()
        onNodeWithText("Hooks don't run. The guards still do.").assertExists()
        onNodeWithText("Allow an empty commit").assertExists()
        onNodeWithText("Ann Lee").performClick()
        assertEquals(listOf<CommitEvent>(CommitEvent.CoAuthorAdded(ann)), events)
    }

    @Test
    fun `busy disables the button and says what runs`() = runDesktopComposeUiTest(360, 520) {
        box(ready.copy(busy = "Committing 3 files…"))
        onNodeWithText("Committing…").assertExists()
    }

    @Test
    fun `an error can be dismissed, and the toast offers undo`() = runDesktopComposeUiTest(360, 560) {
        val error = (failure("COMM") as com.srctool.sinew.models.Result.Failure).error
        box(ready.copy(error = error, toast = CommitToast("Committed a1b2c3d", true)))
        onNodeWithText("Dismiss").performClick()
        onNodeWithText("Undo").performClick()
        assertEquals(listOf<CommitEvent>(CommitEvent.ErrorDismissed, CommitEvent.UndoClicked), events)
    }

    @Test
    fun `the guard dialog reads what was found, masked`() = runDesktopComposeUiTest(520, 600) {
        box(ready.copy(guard = GuardDialog(listOf(block, env, large))))
        onNodeWithText("Commit stopped: 3 problems").assertExists()
        onNodeWithText("Nothing was committed.").assertExists()
        onNodeWithText("ghp_****…a91F").assertExists()
        onNodeWithContentDescription("Block. GitHub token in src/login/Config.kt line 14. ghp_****…a91F")
            .assertExists()
        onNodeWithText("Git LFS suits files over 10 MB.").assertExists()
        onNodeWithText("Commit anyway").assertExists()
    }

    @Test
    fun `its buttons report what was chosen`() = runDesktopComposeUiTest(520, 700) {
        box(ready.copy(guard = GuardDialog(listOf(block, env))))
        onNodeWithText("Open at line").performClick()
        onAllNodesWithText("Allow once")[0].performClick()
        onAllNodesWithText("Allow always…")[0].performClick()
        onAllNodesWithText("Unstage file")[1].performClick()
        onNodeWithText("Cancel").performClick()
        assertEquals(
            listOf(
                CommitEvent.OpenAtLine("src/login/Config.kt"),
                CommitEvent.AllowOnce("b1"),
                CommitEvent.AllowAlwaysAsked("b1"),
                CommitEvent.UnstageFile(".env"),
                CommitEvent.GuardCancelled,
            ),
            events,
        )
    }

    @Test
    fun `allow always asks for you or the team`() = runDesktopComposeUiTest(520, 600) {
        box(ready.copy(guard = GuardDialog(listOf(block), asking = "b1")))
        onNodeWithText("You only").performClick()
        onNodeWithText("The team").performClick()
        val expected = listOf<CommitEvent>(CommitEvent.AllowAlways("b1", false), CommitEvent.AllowAlways("b1", true))
        assertEquals(expected, events)
    }

    @Test
    fun `commit anyway stays off until each block is answered`() {
        runDesktopComposeUiTest(520, 600) {
            box(ready.copy(guard = GuardDialog(listOf(block))))
            onNodeWithText("Commit anyway").performClick()
            assertTrue(events.isEmpty())
        }
        runDesktopComposeUiTest(520, 600) {
            box(ready.copy(guard = GuardDialog(listOf(block), answered = setOf("b1"))))
            onNodeWithText("Allowed once").assertExists()
            onNodeWithText("Commit anyway").performClick()
            assertEquals(listOf<CommitEvent>(CommitEvent.CommitAnyway), events)
        }
    }

    @Test
    fun `an amend's dialog says amend`() = runDesktopComposeUiTest(520, 600) {
        box(ready.copy(amend = true, guard = GuardDialog(listOf(block), amend = true)))
        onNodeWithText("Amend stopped: 1 problems").assertExists()
        onNodeWithText("Amend anyway").assertExists()
    }

    // ---- screenshots -----------------------------------------------------------------------------------------------

    private fun shot(name: String, mode: GraftThemeMode, width: Int, height: Int, state: CommitState) =
        runDesktopComposeUiTest(width, height) {
            setContent { GraftTheme(mode) { CommitBox(state, {}) } }
            Thread.sleep(SETTLE_MS)
            waitForIdle()
            // A dialog is a second root; it is the one drawn last, and the picture of it is the one to keep.
            val roots = onAllNodes(isRoot())
            val file = "src/test/snapshots/$name-${mode.name.lowercase()}.png"
            roots[roots.fetchSemanticsNodes().lastIndex].captureRoboImage(file)
        }

    private val filled =
        ready.copy(
            description = "Raise it to 60 s on slow networks.\n" +
                "The second line of this description is much too long to fit within the usual limit.",
        )
    private val longSummary = ready.copy(summary = "Fix the login timeout that appeared after the retry change")
    private val amending = ready.copy(amend = true, summary = "Earlier")
    private val withOptions =
        ready.copy(
            optionsOpen = true,
            signOff = true,
            noVerify = true,
            coAuthors = listOf(CoAuthor("Bo Chen", "bo@example.invalid")),
            recent = listOf(CoAuthor("Ann Lee", "ann@example.invalid")),
        )
    private val failed =
        ready.copy(error = (failure("COMM") as com.srctool.sinew.models.Result.Failure).error)
    private val done = CommitState(loaded = true, staged = 0, toast = CommitToast("Committed a1b2c3d", true))
    private val blocked = ready.copy(guard = GuardDialog(listOf(block, env, large)))
    private val warned = ready.copy(guard = GuardDialog(listOf(large)))
    private val asking = ready.copy(guard = GuardDialog(listOf(block), asking = "b1"))
    private val empty = CommitState(loaded = true, staged = 3)
    private val busy = ready.copy(busy = "Committing 3 files…")

    private fun both(name: String, width: Int, height: Int, state: CommitState) {
        val light = runCatching { shot(name, GraftThemeMode.LIGHT, width, height, state) }
        val dark = runCatching { shot(name, GraftThemeMode.DARK, width, height, state) }
        light.getOrThrow()
        dark.getOrThrow()
    }

    // Both themes are drawn even if the first has no golden yet, so CI has a render of each to keep.
    @Test fun `shot empty`() = both("commit-empty", 360, 330, empty)

    @Test fun `shot filled`() = both("commit-filled", 360, 360, filled)

    @Test fun `shot long`() = both("commit-long", 360, 330, longSummary)

    @Test fun `shot amend`() = both("commit-amend", 360, 330, amending)

    @Test fun `shot options`() = both("commit-options", 360, 640, withOptions)

    @Test fun `shot busy`() = both("commit-busy", 360, 330, busy)

    @Test fun `shot error`() = both("commit-error", 360, 380, failed)

    @Test fun `shot done`() = both("commit-done", 360, 330, done)

    @Test fun `shot guard`() = both("guard-blocked", 560, 520, blocked)

    @Test fun `shot guard warnings`() = both("guard-warnings", 560, 360, warned)

    @Test fun `shot guard ask`() = both("guard-ask", 560, 400, asking)

    private companion object {
        const val SETTLE_MS = 400L
    }
}
