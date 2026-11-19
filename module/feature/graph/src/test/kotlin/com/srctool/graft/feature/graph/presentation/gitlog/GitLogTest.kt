// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.gitlog

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.srctool.graft.core.ui.theme.GraftTheme
import com.srctool.graft.core.ui.theme.GraftThemeMode
import com.srctool.graft.data.git.api.models.GitLogEntry
import com.srctool.graft.data.git.api.repositories.GitLogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class GitLogTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val folder = Paths.get("repo")
    private val feed = MutableStateFlow<List<GitLogEntry>>(emptyList())
    private val log =
        object : GitLogRepository {
            override fun entries(folder: Path): Flow<List<GitLogEntry>> = feed
        }

    private fun entry(command: String, exit: Int? = 0) = GitLogEntry(1_000, folder, command, 7, exit)

    private fun opened(): GitLogViewModel {
        Dispatchers.setMain(dispatcher)
        val vm =
            ViewModelProvider.create(
                store,
                viewModelFactory { initializer { GitLogViewModel(folder, log) } },
            )[GitLogViewModel::class]
        vm.onEvent(GitLogEvent.Opened)
        return vm
    }

    private fun done() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `commands arrive as they run and the selection stays on its command`() = runTest(dispatcher) {
        val vm = opened()
        feed.value = listOf(entry("status"), entry("log"))
        advanceUntilIdle()
        vm.onEvent(GitLogEvent.Selected(1))
        advanceUntilIdle()
        // The ring dropped the oldest and added a new one: "log" is now row 0, still selected.
        feed.value = listOf(entry("log"), entry("diff"))
        advanceUntilIdle()
        assertEquals("log", vm.state.value.selectedEntry?.command)
        assertEquals(0, vm.state.value.selected)
        done()
    }

    @Test
    fun `failures only hides the commands that succeeded`() = runTest(dispatcher) {
        val vm = opened()
        feed.value = listOf(entry("status"), entry("push", exit = 1), entry("fetch", exit = null))
        advanceUntilIdle()
        vm.onEvent(GitLogEvent.FailedOnly(true))
        advanceUntilIdle()
        assertEquals(listOf("push", "fetch"), vm.state.value.shown.map { it.command })
        done()
    }

    @Test
    fun `the arrows move through the rows and C copies the command`() = runTest(dispatcher) {
        val vm = opened()
        val effects = mutableListOf<GitLogEffect>()
        val collector = launch { vm.effects.collect { effects += it } }
        feed.value = listOf(entry("status"), entry("log"))
        advanceUntilIdle()
        vm.onEvent(GitLogEvent.Moved(1))
        vm.onEvent(GitLogEvent.Moved(1))
        vm.onEvent(GitLogEvent.Moved(1))
        vm.onEvent(GitLogEvent.CopyCommand)
        advanceUntilIdle()
        assertEquals(1, vm.state.value.selected)
        assertEquals(listOf<GitLogEffect>(GitLogEffect.CopyToClipboard("git log")), effects)
        collector.cancel()
        done()
    }

    @Test
    fun `a row reads as its time, command, outcome and duration`() = runDesktopComposeUiTest(700, 200) {
        setContent {
            GraftTheme(GraftThemeMode.LIGHT) {
                GitLogContent(GitLogState(entries = listOf(entry("push origin main", exit = 1))), {})
            }
        }
        onNodeWithText("git push origin main").assertExists()
        onNodeWithContentDescription("Git log").assertExists()
    }

    @Test
    fun `an empty log says nothing has run`() = runDesktopComposeUiTest(700, 200) {
        setContent { GraftTheme(GraftThemeMode.LIGHT) { GitLogContent(GitLogState(), {}) } }
        onNodeWithText("No git command has run yet.").assertExists()
    }
}
