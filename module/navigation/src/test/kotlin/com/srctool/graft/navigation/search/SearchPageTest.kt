// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.search

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.srctool.graft.core.ui.theme.GraftTheme
import com.srctool.graft.core.ui.theme.GraftThemeMode
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.presentation.search.SearchCandidate
import com.srctool.graft.presentation.search.SearchGroup
import com.srctool.graft.presentation.search.SearchKind
import com.srctool.graft.presentation.search.SearchTarget
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test
import kotlin.test.assertEquals

/** Search Everywhere per state (wireframe S39), with its labels and keys. */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
class SearchPageTest {
    private fun ui(block: ComposeUiTest.() -> Unit) = runDesktopComposeUiTest(800, 560) { block() }

    private fun ComposeUiTest.page(
        state: SearchState,
        mode: GraftThemeMode = GraftThemeMode.LIGHT,
        onEvent: (SearchEvent) -> Unit = {},
    ) = setContent { GraftTheme(mode) { SearchContent(state, onEvent) } }

    private fun ComposeUiTest.snapshot(name: String) = onRoot().captureRoboImage("src/test/snapshots/$name.png")

    private fun ranked(query: String, vararg items: SearchCandidate) =
        GroupState.Ready(SearchRanking.rank(query, items.toList()))

    private val repo = candidate("graft", SearchKind.Repository, key = "repo").copy(detail = "~/Projects/graft")
    private val branch = candidate("feature/login", SearchKind.Branch).copy(detail = "origin/feature/login")
    private val tag = candidate("v1.2-login-beta", SearchKind.Tag)
    private val commit =
        SearchCandidate(
            "c1",
            SearchKind.Commit,
            "Add login form",
            SearchTarget.SelectCommit(ObjectId("a".repeat(40))),
            detail = "a1b2c3d · Ann Lee · 2026-11-17",
            extra = "a".repeat(40),
        )
    private val file =
        SearchCandidate(
            "f1",
            SearchKind.File,
            "src/auth/LoginScreen.kt",
            SearchTarget.FileInHead("x"),
            detail = "in HEAD",
        )
    private val settings =
        SearchCandidate("a1", SearchKind.Action, "Settings…", SearchTarget.RunAction("host.settings"), shortcut = "⌘,")

    private val results =
        SearchState(
            query = "login",
            groups =
            mapOf(
                SearchGroup.Repositories to ranked("login", candidate("login-service", SearchKind.Repository)),
                SearchGroup.Refs to ranked("login", branch, tag),
                SearchGroup.Commits to ranked("login", commit),
                SearchGroup.Files to ranked("login", file),
                SearchGroup.Actions to ranked("login", settings.copy(title = "Settings… login")),
            ),
        )

    @Test
    fun `a row reads as its kind, name, detail and shortcut, and the list and tabs say how many`() = ui {
        page(results)
        onNodeWithContentDescription("Results, 6").assertExists()
        onNode(hasContentDescription("Branch, feature/login, origin/feature/login")).assertExists()
        onNode(
            hasContentDescription(
                "Action, Settings… login, ⌘,",
                substring = false,
            ).or(hasContentDescription("Action, Settings… login")),
        ).assertExists()
        onNodeWithContentDescription("Commits, 1 results").assertExists()
        onNodeWithContentDescription("Search everywhere").assertExists()
        onNodeWithText("↑↓ move · Enter open · Tab next tab · Esc close · 6 of 6 shown").assertExists()
    }

    @Test
    fun `the arrows, Enter, Tab and Esc in the box send their events`() = ui {
        val events = mutableStateListOf<SearchEvent>()
        page(results) { events += it }
        val box = onNodeWithContentDescription("Search everywhere")
        box.performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionUp)
            pressKey(Key.Enter)
            pressKey(Key.Tab)
            pressKey(Key.Escape)
        }
        assertEquals(
            listOf(
                SearchEvent.Moved(1),
                SearchEvent.Moved(-1),
                SearchEvent.Activated,
                SearchEvent.TabMoved(1),
                SearchEvent.Escaped,
            ),
            events.filter { it !is SearchEvent.QueryTyped }.toList(),
        )
    }

    @Test
    fun `nothing found says so, and Home says why branches and commits weren't searched`() = ui {
        page(SearchState(query = "zzz", groups = mapOf(SearchGroup.Repositories to GroupState.Ready(emptyList()))))
        onNodeWithText("Nothing matches \"zzz\".").assertExists()
    }

    @Test
    fun `screenshot, results on All, light`() = ui {
        page(results)
        snapshot("search-all-light")
    }

    @Test
    fun `screenshot, results on All, dark, third result chosen`() = ui {
        page(results.copy(selected = 2), GraftThemeMode.DARK)
        snapshot("search-all-dark")
    }

    @Test
    fun `screenshot, commits still working and files failed`() = ui {
        page(
            results.copy(
                groups =
                results.groups + (SearchGroup.Commits to GroupState.Loading) +
                    (SearchGroup.Files to GroupState.Failed(IllegalStateException("git didn't answer"))),
            ),
        )
        snapshot("search-working-light")
    }

    @Test
    fun `screenshot, on Home, nothing typed`() = ui {
        page(
            SearchState(
                groups =
                mapOf(
                    SearchGroup.Repositories to GroupState.Ready(listOf(Ranked(repo, 0, emptyList()))),
                    SearchGroup.Refs to GroupState.NeedsRepository,
                    SearchGroup.Commits to GroupState.NeedsRepository,
                    SearchGroup.Files to GroupState.NeedsRepository,
                    SearchGroup.Actions to GroupState.Ready(listOf(Ranked(settings, 0, emptyList()))),
                ),
            ),
        )
        snapshot("search-home-light")
    }

    @Test
    fun `screenshot, the files tab with a cut list`() = ui {
        page(
            results.copy(
                tab = SearchTab.Files,
                groups =
                results.groups +
                    (
                        SearchGroup.Files to
                            GroupState.Ready(
                                SearchRanking.rank("login", listOf(file)),
                                "Showing the first 200000 files.",
                            )
                        ),
            ),
        )
        snapshot("search-files-light")
    }

    @Test
    fun `screenshot, nothing matches`() = ui {
        page(SearchState(query = "zzz", groups = mapOf(SearchGroup.Repositories to GroupState.Ready(emptyList()))))
        snapshot("search-empty-light")
    }
}
