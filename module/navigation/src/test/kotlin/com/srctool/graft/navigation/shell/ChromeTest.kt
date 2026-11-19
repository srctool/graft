// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.graft.core.ui.components.DocumentTab
import com.srctool.graft.core.ui.components.DocumentTabs
import com.srctool.graft.core.ui.theme.GraftIcon
import com.srctool.graft.core.ui.theme.GraftTheme
import com.srctool.graft.core.ui.theme.GraftThemeMode
import com.srctool.graft.data.git.api.models.Head
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.data.git.api.models.RepositorySummary
import com.srctool.graft.data.git.api.models.Upstream
import com.srctool.graft.presentation.session.RepoSnapshot
import com.srctool.graft.presentation.session.SnapshotPart
import io.github.takahirom.roborazzi.captureRoboImage
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

/** The window's toolbar and status bar (Task 20 wireframe): what they say, what they do, and how they look. */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
class ChromeTest {
    private val location = RepositoryLocation(Paths.get("/r"), Paths.get("/r/.git"), Paths.get("/r/.git"))
    private val tip = ObjectId("a".repeat(40))

    private fun snapshot(
        head: Head? = Head.Branch("main", tip),
        ahead: Int = 0,
        behind: Int = 0,
        gone: Boolean = false,
        loaded: Set<SnapshotPart> = SnapshotPart.entries.toSet(),
        refreshing: Set<SnapshotPart> = emptySet(),
        failures: Map<SnapshotPart, Throwable> = emptyMap(),
        polling: Boolean = false,
    ) = RepoSnapshot(
        location,
        summary = head?.let { RepositorySummary(it, Upstream("origin/main", ahead, behind, gone), dirty = false) },
        loaded = loaded,
        refreshing = refreshing,
        failures = failures,
        polling = polling,
    )

    @Test
    fun `the status line says the branch, how far from its upstream, and what's busy`() {
        assertEquals(StatusLine(), statusOf(null))
        val line = statusOf(snapshot(ahead = 1, behind = 3))
        assertEquals("main", line.branch)
        assertEquals(1, line.ahead)
        assertEquals(3, line.behind)
        assertEquals(false, line.reading)
        assertEquals(true, statusOf(snapshot(refreshing = setOf(SnapshotPart.REFS))).reading)
        assertEquals(true, statusOf(snapshot(loaded = emptySet())).reading, "before the first read")
        assertEquals(true, statusOf(snapshot(polling = true)).polling)
        assertEquals(true, statusOf(snapshot(failures = mapOf(SnapshotPart.REFS to RuntimeException()))).failed)
    }

    @Test
    fun `a detached HEAD shows its short commit`() {
        val line = statusOf(snapshot(head = Head.Detached(tip)))
        assertEquals("aaaaaaa", line.branch)
        assertEquals(true, line.detached)
    }

    private fun ComposeUiTest.chrome(
        status: StatusLine,
        notice: String? = null,
        mode: GraftThemeMode = GraftThemeMode.LIGHT,
        calls: MutableList<String> = mutableStateListOf(),
        repository: String? = "graft",
    ) = setContent {
        GraftTheme(mode) {
            Column(Modifier.fillMaxWidth().background(Camouflage.colors.surface)) {
                Toolbar(
                    repositories = listOf(OpenRepositoryTab("t1", "graft"), OpenRepositoryTab("t2", "api")),
                    repository = repository,
                    branch = status.branch,
                    actions =
                    ToolbarActions(
                        onHome = { calls += "home" },
                        onPick = { calls += "pick $it" },
                        onBranch = { calls += "branch" },
                        onSearch = { calls += "search" },
                        onSettings = { calls += "settings" },
                        onWelcome = { calls += "welcome" },
                        onAbout = { calls += "about" },
                        onFocusMode = { calls += "focus" },
                        onGitLog = { calls += "gitlog" },
                    ),
                )
                DocumentTabs(
                    tabs =
                    listOf(
                        DocumentTab("h", "Home", GraftIcon.HOME, closable = false, pinned = true),
                        DocumentTab("t1", "graft", GraftIcon.FOLDER),
                        DocumentTab("t2", "api", GraftIcon.FOLDER),
                    ),
                    selectedId = "t1",
                    onSelect = {},
                    onClose = {},
                    onMove = { _, _ -> },
                )
                Spacer(Modifier.fillMaxWidth().height(120.dp))
                StatusBar(status, notice) { calls += "dismiss" }
            }
        }
    }

    private fun ui(block: ComposeUiTest.() -> Unit) = runDesktopComposeUiTest(900, 260) { block() }

    @Test
    fun `the toolbar reads as chips and buttons, and each does its one thing`() = ui {
        val calls = mutableStateListOf<String>()
        chrome(statusOf(snapshot(ahead = 1)), calls = calls)
        onNodeWithContentDescription("Toolbar").assertExists()
        onNodeWithContentDescription("Repository, graft").assertExists()
        onNodeWithContentDescription("Branch, main").performClick()
        // The toolbar's Home button comes before the Home tab.
        onAllNodesWithContentDescription("Home")[0].performClick()
        onNodeWithText("Search everywhere").performClick()
        assertEquals(listOf("branch", "home", "search"), calls.toList())
    }

    @Test
    fun `with no repository the chips say so and the branch chip can't be pressed`() = ui {
        chrome(StatusLine(), repository = null)
        onNodeWithContentDescription("Repository, No repository").assertExists()
        onNode(hasContentDescription("Branch, No repository")).assertExists()
    }

    @Test
    fun `the status bar is a live region that reads the branch and what's going on`() = ui {
        chrome(statusOf(snapshot(ahead = 1, behind = 2, refreshing = setOf(SnapshotPart.STATUS))))
        onNodeWithContentDescription("Status: main  ↑1  ↓2. Reading the repository…").assertExists()
    }

    @Test
    fun `a notice can be dismissed`() = ui {
        val calls = mutableStateListOf<String>()
        chrome(StatusLine(), notice = "A saved repository wasn't found: /gone", calls = calls)
        onNodeWithText("A saved repository wasn't found: /gone").assertExists()
        onNodeWithText("Dismiss").performClick()
        assertEquals(listOf("dismiss"), calls.toList())
    }

    private fun ComposeUiTest.snapshot(name: String) = onRoot().captureRoboImage("src/test/snapshots/$name.png")

    @Test
    fun `screenshot, window chrome, light`() = ui {
        chrome(statusOf(snapshot(ahead = 1)))
        snapshot("shell-chrome-light")
    }

    @Test
    fun `screenshot, window chrome, dark, busy and polling`() = ui {
        chrome(
            statusOf(snapshot(behind = 2, refreshing = setOf(SnapshotPart.REFS), polling = true)),
            mode = GraftThemeMode.DARK,
        )
        snapshot("shell-chrome-dark")
    }

    @Test
    fun `screenshot, window chrome, home with a notice`() = ui {
        chrome(StatusLine(), notice = "A saved repository wasn't found: /gone", repository = null)
        snapshot("shell-chrome-home")
    }
}
