// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.srctool.graft.core.ui.git.DiffMode
import com.srctool.graft.core.ui.theme.GraftTheme
import com.srctool.graft.core.ui.theme.GraftThemeMode
import com.srctool.graft.data.git.api.models.DiffLine
import com.srctool.graft.data.git.api.models.Hunk
import com.srctool.graft.data.git.api.models.Ref
import com.srctool.graft.feature.graph.presentation.branches.BranchesContent
import com.srctool.graft.feature.graph.presentation.branches.BranchesEvent
import com.srctool.graft.feature.graph.presentation.branches.BranchesState
import com.srctool.graft.feature.graph.presentation.branches.PanelInput
import com.srctool.graft.feature.graph.presentation.branches.panelRows
import com.srctool.graft.feature.graph.presentation.details.DetailsContent
import com.srctool.graft.feature.graph.presentation.details.DetailsEvent
import com.srctool.graft.feature.graph.presentation.details.DetailsState
import com.srctool.graft.feature.graph.presentation.details.fileRows
import com.srctool.graft.feature.graph.presentation.diff.DiffBody
import com.srctool.graft.feature.graph.presentation.diff.DiffScreenContent
import com.srctool.graft.feature.graph.presentation.diff.DiffScreenEvent
import com.srctool.graft.feature.graph.presentation.diff.DiffScreenState
import com.srctool.graft.feature.graph.presentation.diff.ImagePair
import com.srctool.graft.feature.graph.presentation.diff.hunkView
import com.srctool.graft.feature.graph.presentation.graph.FakeFailure
import com.srctool.graft.feature.graph.presentation.graph.GraphContent
import com.srctool.graft.feature.graph.presentation.graph.GraphState
import com.srctool.graft.feature.graph.presentation.graph.Sample
import com.srctool.graft.feature.graph.presentation.graph.Selection
import com.srctool.graft.feature.graph.presentation.graph.StashPlacement
import com.srctool.graft.feature.graph.presentation.graph.refLabels
import com.srctool.graft.feature.graph.presentation.graph.sampleFiles
import com.srctool.graft.feature.graph.presentation.graph.sampleStash
import io.github.takahirom.roborazzi.captureRoboImage
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals

/** The left panel, commit details and diff screen per state (Screen Inventory), with their labels and keys. */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
class ScreensPageTest {
    private fun ui(width: Int, height: Int, block: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width, height) { block() }

    private fun ComposeUiTest.themed(mode: GraftThemeMode = GraftThemeMode.LIGHT, content: @Composable () -> Unit) =
        setContent { GraftTheme(mode) { content() } }

    private fun ComposeUiTest.snapshot(name: String) {
        // Syntax colors are computed off the main thread; without a moment the diff pictures race them on a slow runner.
        if (name.startsWith("diff-screen")) {
            Thread.sleep(400)
            waitForIdle()
        }
        onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    private val refs =
        Sample.refs +
            listOf(
                Ref("refs/heads/feature/logout", Sample.id(3)),
                Ref("refs/heads/old-work", Sample.id(1)),
                Ref("refs/remotes/origin/release/1.4", Sample.id(2)),
            )

    private val panel =
        BranchesState(
            rows =
            panelRows(
                PanelInput(
                    refs,
                    listOf(sampleStash),
                    hidden = setOf("refs/heads/old-work"),
                    solo = setOf("refs/heads/feature/login"),
                    pinned = setOf("refs/heads/main"),
                ),
            ),
            phase = BranchesState.Phase.Ready,
            focus = "ref:refs/heads/main",
        )

    private val details = Sample.details(Sample.rows[0])

    private val loadedDetails =
        DetailsState(
            phase = DetailsState.Phase.Loaded,
            details = details,
            rows = fileRows(details.files, tree = true),
            now = Sample.now,
        )

    private val diffState =
        DiffScreenState(
            target = com.srctool.graft.data.git.api.models.DiffTarget.Commit(Sample.id(5)),
            subject = "Fix login timeout",
            files = sampleFiles,
            index = 0,
            body =
            DiffBody.Text(
                com.srctool.graft.core.ui.git.DiffDocument(
                    sampleFiles[0].path,
                    listOf(
                        hunkView(
                            Hunk(
                                10,
                                4,
                                10,
                                5,
                                "class Login",
                                listOf(
                                    DiffLine(DiffLine.Kind.CONTEXT, 10, 10, "    fun login(user: String) {"),
                                    DiffLine(DiffLine.Kind.REMOVED, 11, null, "        val timeout = 30 // seconds"),
                                    DiffLine(DiffLine.Kind.ADDED, null, 11, "        val timeout = 60 // seconds"),
                                    DiffLine(DiffLine.Kind.ADDED, null, 12, "        log(\"login: \$user\")"),
                                    DiffLine(DiffLine.Kind.CONTEXT, 12, 13, "    }"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

    // Labels and keys

    @Test
    fun `panel rows are read with their state, and the panel's keys reach it`() = ui(320, 420) {
        val events = mutableStateListOf<BranchesEvent>()
        themed { BranchesContent(panel) { events += it } }
        onNodeWithContentDescription("Branch main, checked out, 1 ahead, pinned").assertExists()
        onNodeWithContentDescription("Branch old-work, hidden").assertExists()
        onNodeWithContentDescription("Branch feature/login, shown alone").assertExists()
        onNodeWithContentDescription("Stash 0, WIP on main: 4444444 Fix login timeout, made on main").assertExists()
        val list = onNodeWithContentDescription("Branches")
        list.requestFocus()
        list.performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionLeft)
            pressKey(Key.DirectionRight)
            pressKey(Key.Enter)
            withKeyDown(Key.ShiftLeft) { pressKey(Key.F10) }
        }
        assertEquals(
            listOf(
                BranchesEvent.Moved(1),
                BranchesEvent.Collapse,
                BranchesEvent.Expand,
                BranchesEvent.Activated("ref:refs/heads/main"),
                BranchesEvent.MenuShown("ref:refs/heads/main"),
            ),
            events.filterNot { it is BranchesEvent.Searched },
        )
    }

    @Test
    fun `details read the signature and each file, and the files take arrows and Enter`() = ui(420, 600) {
        val events = mutableStateListOf<DetailsEvent>()
        themed { DetailsContent(loadedDetails.copy(focus = "file:src/main/kotlin/login/Login.kt")) { events += it } }
        onNodeWithContentDescription("Signed, good signature").assertExists()
        onNodeWithContentDescription(
            "Modified src/main/kotlin/login/Login.kt, 12 lines added, 3 removed",
        ).assertExists()
        onNodeWithContentDescription(
            "Renamed src/main/kotlin/login/Old.kt to src/main/kotlin/login/Retry.kt, 1 lines added, 1 removed",
        )
            .assertExists()
        onNodeWithContentDescription("Modified docs/logo.png, binary").assertExists()
        val files = onNodeWithContentDescription("Changed files")
        files.requestFocus()
        files.performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
        }
        assertEquals(
            listOf(DetailsEvent.Moved(1), DetailsEvent.Activated("file:src/main/kotlin/login/Login.kt")),
            events.toList(),
        )
    }

    @Test
    fun `the diff screen goes back with Esc and steps files with Alt+Shift+arrows`() = ui(900, 300) {
        val events = mutableStateListOf<DiffScreenEvent>()
        themed { DiffScreenContent(diffState) { events += it } }
        onNodeWithContentDescription("File 1 of 5, src/main/kotlin/login/Login.kt").assertExists()
        onNodeWithContentDescription("Back to the graph").assertExists()
        val viewer = onNodeWithContentDescription("Line 10:     fun login(user: String) {")
        viewer.assertExists()
        onRoot().performKeyInput {
            withKeyDown(Key.AltLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionRight) } }
            pressKey(Key.Escape)
        }
        assertEquals(listOf(DiffScreenEvent.StepFile(+1), DiffScreenEvent.Back), events.toList())
    }

    // Screenshots

    @Test
    fun `screenshot, left panel, light`() = ui(320, 420) {
        themed { BranchesContent(panel) {} }
        snapshot("panel-light")
    }

    @Test
    fun `screenshot, left panel, dark`() = ui(320, 420) {
        themed(GraftThemeMode.DARK) { BranchesContent(panel) {} }
        snapshot("panel-dark")
    }

    @Test
    fun `screenshot, left panel, refs failed`() = ui(320, 160) {
        themed { BranchesContent(BranchesState(phase = BranchesState.Phase.Failed, error = FakeFailure("refs"))) {} }
        snapshot("panel-failed-light")
    }

    @Test
    fun `screenshot, details, light`() = ui(420, 600) {
        themed { DetailsContent(loadedDetails) {} }
        snapshot("details-light")
    }

    @Test
    fun `screenshot, details, dark`() = ui(420, 600) {
        themed(GraftThemeMode.DARK) { DetailsContent(loadedDetails) {} }
        snapshot("details-dark")
    }

    @Test
    fun `screenshot, details, nothing selected and the WIP row`() = ui(420, 120) {
        themed { DetailsContent(DetailsState(phase = DetailsState.Phase.Wip, wipFiles = 4)) {} }
        snapshot("details-wip-light")
    }

    @Test
    fun `screenshot, details, failed`() = ui(420, 160) {
        themed { DetailsContent(DetailsState(phase = DetailsState.Phase.Failed, error = FakeFailure("gone"))) {} }
        snapshot("details-failed-light")
    }

    @Test
    fun `screenshot, diff, unified, light`() = ui(900, 300) {
        themed { DiffScreenContent(diffState) {} }
        snapshot("diff-screen-light")
    }

    @Test
    fun `screenshot, diff, side by side, dark`() = ui(900, 300) {
        themed(GraftThemeMode.DARK) { DiffScreenContent(diffState.copy(mode = DiffMode.SIDE_BY_SIDE)) {} }
        snapshot("diff-screen-split-dark")
    }

    @Test
    fun `screenshot, diff, image`() = ui(900, 300) {
        val images = ImagePair(png(Color.makeRGB(97, 175, 239)), png(Color.makeRGB(152, 195, 121)))
        themed { DiffScreenContent(diffState.copy(index = 3, body = DiffBody.Binary(1_200, 1_350, images))) {} }
        snapshot("diff-screen-image-light")
    }

    @Test
    fun `screenshot, diff, too large`() = ui(900, 200) {
        themed { DiffScreenContent(diffState.copy(body = DiffBody.TooLarge(5_000_000))) {} }
        snapshot("diff-screen-large-light")
    }

    @Test
    fun `screenshot, graph with a stash`() = ui(1000, 300) {
        val graph = Sample.graph()
        themed {
            GraphContent(
                GraphState(
                    graph = graph,
                    rowCount = Sample.rows.size,
                    widest = graph.widest,
                    phase = GraphState.Phase.Loaded,
                    refs = refLabels(Sample.refs),
                    head = Sample.id(5),
                    stashes = listOf(StashPlacement(sampleStash, 1)),
                    selection = Selection.Stash(sampleStash.id),
                    now = Sample.now,
                ),
                {},
                skeletonDelayMs = 0,
            )
        }
        snapshot("graph-stash-light")
    }

    /** A small two-tone PNG, as an image file in a commit would be. */
    private fun png(color: Int): ByteArray {
        val surface = Surface.makeRasterN32Premul(64, 48)
        surface.canvas.clear(Color.WHITE)
        surface.canvas.drawRect(Rect.makeXYWH(8f, 8f, 48f, 32f), Paint().apply { this.color = color })
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }
}
