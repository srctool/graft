// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.graph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.srctool.graft.core.exception.CommandOutput
import com.srctool.graft.core.ui.theme.GraftTheme
import com.srctool.graft.core.ui.theme.GraftThemeMode
import com.srctool.graft.data.app.api.models.GraphAuthorKind
import com.srctool.graft.data.app.api.models.GraphViewState
import com.srctool.graft.feature.graph.presentation.graph.GraphState.Phase
import com.srctool.sinew.models.ErrorMessage
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.SinewException
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test
import kotlin.test.assertEquals

/** The page per state (Screen Inventory), its screen-reader labels and its keyboard path. */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
class GraphPageTest {
    private fun ui(width: Int = 1000, height: Int = 300, block: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width, height) { block() }

    private fun ComposeUiTest.page(
        state: GraphState,
        mode: GraftThemeMode = GraftThemeMode.LIGHT,
        onEvent: (GraphEvent) -> Unit = {},
    ) = setContent { Themed(mode) { GraphContent(state, onEvent, skeletonDelayMs = 0) } }

    @Composable
    private fun Themed(mode: GraftThemeMode, content: @Composable () -> Unit) = GraftTheme(mode) { content() }

    private fun ComposeUiTest.snapshot(name: String) = onRoot().captureRoboImage("src/test/snapshots/$name.png")

    private val loaded =
        GraphState(
            graph = Sample.graph(),
            rowCount = Sample.rows.size,
            widest = Sample.graph().widest,
            phase = Phase.Loaded,
            refs = refLabels(Sample.refs),
            head = Sample.id(5),
            wipFiles = 4,
            selection = Selection.Row(1),
            now = Sample.now,
        )

    /** A git failure, as the data layer reports one: a code, and git's output for Show output. */
    private class LogFailed :
        SinewException("GIT", ExceptionLayer.Repository, "LOG"),
        CommandOutput {
        override val typeCode = "EXIT"
        override val errorMessage = ErrorMessage.Local(
            "graph.test.failed",
            emptyMap(),
            "Git couldn't read the history.",
        )
        override val argv = listOf("git", "log", "--topo-order")
        override val exitCode = 128
        override val stderr = "fatal: bad object refs/heads/broken"
    }

    @Test
    fun `each row is read as one sentence, the WIP row too`() = ui {
        page(loaded)
        onNodeWithContentDescription(
            "Commit 5555555, Merge feature/login, by Dwiki Riyadi, 2 hours ago, refs main ⇄ origin/main, merge of 2 parents",
        ).assertExists()
        onNodeWithContentDescription(
            "Commit 2222222, Update deps, by dependabot[bot], 3 days ago, refs v1.0",
        ).assertExists()
        onNodeWithContentDescription("Uncommitted changes, 4 files").assertExists()
        onNodeWithContentDescription("Commit graph, 5 commits").assertExists()
    }

    @Test
    fun `search matches are named in the row's label`() = ui {
        page(
            loaded.copy(
                search = SearchState("login", matches = setOf(Sample.id(4)), matchRows = listOf(1), current = 0),
            ),
        )
        onNodeWithContentDescription(
            "Commit 4444444, Fix login timeout, by Dwiki Riyadi, 5 hours ago, matches search",
        ).assertExists()
        onNodeWithText("1 of 1").assertExists()
    }

    @Test
    fun `the table's keys send the graph's moves`() = ui {
        val events = mutableStateListOf<GraphEvent>()
        page(loaded) { events += it }
        val table = onNodeWithContentDescription("Commit graph, 5 commits")
        table.requestFocus()
        table.performKeyInput {
            pressKey(Key.DirectionDown)
            withKeyDown(Key.AltLeft) { pressKey(Key.DirectionDown) }
            withKeyDown(Key.AltLeft) { pressKey(Key.DirectionUp) }
            withKeyDown(Key.AltLeft) { pressKey(Key.DirectionRight) }
            pressKey(Key.RightBracket)
            pressKey(Key.LeftBracket)
            pressKey(Key.Enter)
            withKeyDown(Key.CtrlLeft) { pressKey(Key.C) }
        }
        assertEquals(
            listOf(
                GraphEvent.MovedBy(1),
                GraphEvent.FirstParent,
                GraphEvent.FirstChild,
                GraphEvent.OtherParent,
                GraphEvent.NextRef(+1),
                GraphEvent.NextRef(-1),
                GraphEvent.OpenSelected,
                GraphEvent.CopyHash,
            ),
            events.filterNot { it is GraphEvent.ColumnsChanged },
        )
    }

    @Test
    fun `Mod+F goes to search and Esc there clears it and returns to the table`() = ui {
        val events = mutableStateListOf<GraphEvent>()
        page(loaded) { events += it }
        val table = onNodeWithContentDescription("Commit graph, 5 commits")
        table.requestFocus()
        table.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
        onNodeWithText("Search message, hash…").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        table.assertIsFocused()
        assertEquals(listOf<GraphEvent>(GraphEvent.SearchCleared), events.filterNot { it is GraphEvent.ColumnsChanged })
    }

    // Screenshots: every state in Graft Light, the loaded graph and the error in Graft Dark too.

    @Test
    fun `screenshot, loaded, light`() = ui {
        page(loaded)
        snapshot("graph-loaded-light")
    }

    @Test
    fun `screenshot, loaded, dark`() = ui {
        page(loaded, GraftThemeMode.DARK)
        snapshot("graph-loaded-dark")
    }

    @Test
    fun `screenshot, searching and dimmed`() = ui {
        val view = GraphViewState(authorKinds = setOf(GraphAuthorKind.PERSON, GraphAuthorKind.AI_ASSISTED))
        page(
            loaded.copy(
                view = view,
                search = SearchState(
                    "login",
                    matches = setOf(Sample.id(4), Sample.id(5)),
                    matchRows = listOf(0, 1),
                    current = 1,
                ),
            ),
        )
        snapshot("graph-search-light")
    }

    @Test
    fun `screenshot, loading`() = ui {
        page(GraphState(phase = Phase.Loading))
        snapshot("graph-loading-light")
    }

    @Test
    fun `screenshot, empty repository`() = ui {
        page(GraphState(phase = Phase.Empty))
        snapshot("graph-empty-light")
    }

    @Test
    fun `screenshot, filtered to nothing`() = ui {
        page(GraphState(phase = Phase.Loaded, soloCount = 1, graph = Sample.graph(emptyList())))
        snapshot("graph-filtered-light")
    }

    @Test
    fun `screenshot, git error, light`() = ui {
        page(GraphState(phase = Phase.Failed, error = LogFailed()))
        snapshot("graph-error-light")
    }

    @Test
    fun `screenshot, git error after some rows, dark`() = ui {
        page(loaded.copy(phase = Phase.Failed, error = LogFailed(), rowCount = 2), GraftThemeMode.DARK)
        snapshot("graph-error-rows-dark")
    }

    @Test
    fun `screenshot, commit-graph offer`() = ui {
        page(loaded.copy(commitGraph = CommitGraphOffer.Shown))
        snapshot("graph-offer-light")
    }
}
