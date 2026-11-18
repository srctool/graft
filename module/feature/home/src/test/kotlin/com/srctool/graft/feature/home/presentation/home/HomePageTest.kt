// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.presentation.home

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.srctool.graft.core.ui.theme.GraftTheme
import com.srctool.graft.core.ui.theme.GraftThemeMode
import com.srctool.graft.data.app.api.models.KnownRepository
import com.srctool.graft.data.app.api.models.Workspace
import com.srctool.graft.feature.home.domain.api.models.ScannedRepository
import com.srctool.graft.feature.home.presentation.path
import com.srctool.graft.feature.home.presentation.summary
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test
import kotlin.test.assertEquals

/** Home per state (Screen Inventory), with its labels and keys. */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
class HomePageTest {
    private fun ui(height: Int = 460, block: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(1100, height) { block() }

    private fun ComposeUiTest.page(
        state: HomeState,
        mode: GraftThemeMode = GraftThemeMode.LIGHT,
        onEvent: (HomeEvent) -> Unit = {},
    ) = setContent { GraftTheme(mode) { HomeContent(state, onEvent) } }

    private fun ComposeUiTest.snapshot(name: String) = onRoot().captureRoboImage("src/test/snapshots/$name.png")

    /** A dialog is its own root, over Home's. */
    private fun ComposeUiTest.dialogSnapshot(name: String) =
        onAllNodes(isRoot()).onLast().captureRoboImage("src/test/snapshots/$name.png")

    private val now = 100_000_000L
    private val repositories =
        listOf(
            KnownRepository(path("graft"), lastOpened = now - 7_200, pinned = true, missing = false),
            KnownRepository(path("api"), lastOpened = now - 90_000, pinned = false, missing = false),
            KnownRepository(path("old-thing"), lastOpened = now - 4_000_000, pinned = false, missing = true),
        )
    private val acme = Workspace("acme", "Acme", listOf(path("graft"), path("old-thing")))
    private val ready =
        HomeState(
            phase = HomeState.Phase.Ready,
            repositories = repositories,
            workspaces = listOf(acme),
            summaries =
            mapOf(
                path("graft") to SummaryState.Loaded(summary("main", dirty = true, ahead = 1)),
                path("api") to SummaryState.Loaded(summary("main", behind = 3)),
            ),
            focus = path("graft").toString(),
            now = now,
        )

    @Test
    fun `each row is read whole, with its branch, changes, upstream and age`() = ui {
        page(ready)
        onNodeWithContentDescription("Repositories, 3").assertExists()
        onNode(
            hasContentDescription(
                "graft, ${path("graft")}, branch main, uncommitted changes, 1 ahead",
                substring = true,
            ),
        ).assertExists()
        onNode(hasContentDescription("api, ${path("api")}, branch main, 3 behind", substring = true)).assertExists()
        onNode(hasContentDescription("old-thing, ${path("old-thing")}, missing", substring = true)).assertExists()
    }

    @Test
    fun `the arrows move, Enter opens, Space pins and Delete asks`() = ui {
        val events = mutableStateListOf<HomeEvent>()
        page(ready) { events += it }
        val list = onNodeWithContentDescription("Repositories, 3")
        list.requestFocus()
        list.performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionUp)
            pressKey(Key.Enter)
            pressKey(Key.Spacebar)
            pressKey(Key.Delete)
        }
        val keyed = events.filter { it !is HomeEvent.RowShown }
        assertEquals(
            listOf(
                HomeEvent.Moved(1),
                HomeEvent.Moved(-1),
                HomeEvent.OpenFocused,
                HomeEvent.PinFocused,
                HomeEvent.RemoveFocusedAsked,
            ),
            keyed,
        )
    }

    @Test
    fun `screenshot, home, light`() = ui {
        page(ready)
        snapshot("home-light")
    }

    @Test
    fun `screenshot, home, dark`() = ui {
        page(ready, GraftThemeMode.DARK)
        snapshot("home-dark")
    }

    @Test
    fun `screenshot, a workspace's overview`() = ui {
        page(ready.copy(view = HomeView.Workspace("acme")))
        snapshot("home-workspace-light")
    }

    @Test
    fun `screenshot, home, empty`() = ui(height = 320) {
        page(HomeState(phase = HomeState.Phase.Ready, now = now))
        snapshot("home-empty-light")
    }

    @Test
    fun `screenshot, scanning a folder`() = ui {
        val found =
            listOf(
                ScannedRepository(path("graft"), known = true),
                ScannedRepository(path("camouflage"), known = false),
                ScannedRepository(path("sinew"), known = false),
            )
        page(
            ready.copy(
                dialog =
                HomeDialog.Scan(path("src"), scanning = false, found = found, selected = setOf(path("sinew"))),
            ),
        )
        dialogSnapshot("home-scan-light")
    }

    @Test
    fun `screenshot, a folder that isn't a repository`() = ui {
        page(ready.copy(dialog = HomeDialog.NotARepository(path("notes"))))
        dialogSnapshot("home-not-a-repository-light")
    }
}
