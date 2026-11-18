// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.compare

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
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.srctool.graft.core.ui.theme.GraftTheme
import com.srctool.graft.core.ui.theme.GraftThemeMode
import com.srctool.graft.data.git.api.models.MergePreview
import com.srctool.graft.feature.graph.domain.api.models.CompareSummary
import com.srctool.graft.feature.graph.presentation.details.fileRows
import com.srctool.graft.feature.graph.presentation.graph.Sample
import com.srctool.graft.feature.graph.presentation.graph.sampleFiles
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test
import kotlin.test.assertEquals

/** Compare per state (Screen Inventory), with its labels and keys. */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
class ComparePageTest {
    private fun ui(height: Int = 420, block: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(1000, height) { block() }

    private fun ComposeUiTest.page(
        state: CompareState,
        mode: GraftThemeMode = GraftThemeMode.LIGHT,
        onEvent: (CompareEvent) -> Unit = {
        },
    ) = setContent { GraftTheme(mode) { CompareContent(state, onEvent) } }

    private fun ComposeUiTest.snapshot(name: String) = onRoot().captureRoboImage("src/test/snapshots/$name.png")

    private val people = Sample.people
    private val ready =
        CompareState(
            a = "refs/heads/main",
            b = "refs/heads/feature/login",
            phase = CompareState.Phase.Ready,
            summary = CompareSummary(
                Sample.id(2).hex,
                Sample.id(5).hex,
                ahead = 2,
                behind = 1_201,
                mergeBase = Sample.id(2),
            ),
            preview = MergePreview(emptyList()),
            onlyInB = Sample.rows.take(2).map { CompareCommit(it, people[it.authorIndex].name) },
            onlyInA = Sample.rows.drop(2).take(1).map { CompareCommit(it, people[it.authorIndex].name) },
            files = sampleFiles,
            fileRows = fileRows(sampleFiles, tree = true),
            refs = Sample.refs.map { it.name },
            now = Sample.now,
        )

    @Test
    fun `the summary, the merge preview and the lists are read whole`() = ui {
        page(ready)
        onNodeWithContentDescription("Compare main with feature/login").assertExists()
        onNodeWithContentDescription("Merges cleanly into main").assertExists()
        onNodeWithContentDescription("Commits in feature/login and not in main, 2").assertExists()
        onNodeWithContentDescription("Commits in main and not in feature/login, 1201").assertExists()
        onNodeWithContentDescription("Commit 5555555, Merge feature/login, by Dwiki Riyadi, 2 hours ago").assertExists()
    }

    @Test
    fun `Esc goes back to the graph and the lists take arrows and Enter`() = ui {
        val events = mutableStateListOf<CompareEvent>()
        page(ready.copy(focus = "commit:${Sample.id(5).hex}")) { events += it }
        val lists = onNodeWithContentDescription("Commits and files")
        lists.requestFocus()
        lists.performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
            pressKey(Key.Escape)
        }
        assertEquals(
            listOf(CompareEvent.Moved(1), CompareEvent.Activated("commit:${Sample.id(5).hex}"), CompareEvent.Back),
            events.toList(),
        )
    }

    @Test
    fun `screenshot, compare, light`() = ui {
        page(ready)
        snapshot("compare-light")
    }

    @Test
    fun `screenshot, compare with conflicts, dark`() = ui {
        page(ready.copy(preview = MergePreview(listOf("a.txt", "src/main/kotlin/login/Login.kt"))), GraftThemeMode.DARK)
        snapshot("compare-conflicts-dark")
    }

    @Test
    fun `screenshot, compare, picking B`() = ui(height = 200) {
        page(CompareState(a = "refs/heads/main", phase = CompareState.Phase.Picking))
        snapshot("compare-picking-light")
    }

    @Test
    fun `screenshot, compare, not found`() = ui(height = 200) {
        page(CompareState(a = "refs/heads/main", b = "nope", phase = CompareState.Phase.NotFound, missing = "nope"))
        snapshot("compare-missing-light")
    }
}
