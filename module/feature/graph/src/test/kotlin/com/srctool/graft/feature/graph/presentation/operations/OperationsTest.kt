// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.operations

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.srctool.graft.core.ui.theme.GraftTheme
import com.srctool.graft.core.ui.theme.GraftThemeMode
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.Person
import com.srctool.graft.feature.graph.domain.api.models.TimelineEntry
import com.srctool.graft.feature.graph.domain.api.usecases.ReflogTimeline
import com.srctool.graft.feature.graph.domain.api.usecases.ReflogTimelineUseCase
import com.srctool.graft.feature.graph.presentation.graph.FakeSession
import com.srctool.graft.feature.graph.presentation.graph.FakeUiState
import com.srctool.graft.feature.graph.presentation.graph.Sample
import com.srctool.graft.presentation.view.CenterView
import com.srctool.graft.presentation.view.RepositoryViewState
import com.srctool.graft.presentation.view.ViewSelection
import com.srctool.sinew.models.Result
import io.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [count] moves, newest first; every third a reset. */
internal fun moves(count: Int, now: Long = Sample.now): List<TimelineEntry> = List(count) { i ->
    TimelineEntry(
        refs = if (i % 2 == 0) listOf("HEAD", "refs/heads/main") else listOf("HEAD"),
        time = now - 600L * (i + 1),
        offsetMinutes = 0,
        oldId = if (i == count - 1) null else ObjectId(((i + 2) % 9 + 1).toString().repeat(40)),
        newId = ObjectId(((i + 1) % 9 + 1).toString().repeat(40)),
        actor = Person("Dwiki Riyadi", "dwiki@example.invalid"),
        message = if (i % 3 == 2) "reset: moving to HEAD~3" else "commit: Change $i",
        subject = "Change $i",
    )
}

/** A timeline over [entries], read [page] at a time; records the refs each opening asked for. */
internal class FakeTimelines(var entries: List<TimelineEntry> = moves(5), var enabled: Boolean = true) :
    ReflogTimelineUseCase {
    val opened = ArrayList<List<String>?>()

    override suspend fun open(repository: Path, refs: List<String>?): Result<ReflogTimeline> {
        opened += refs
        val shown = entries.filter { refs == null || refs.any { r -> r in it.refs } }
        return Result.Success(
            object : ReflogTimeline {
                private var at = 0
                override val enabled: Boolean = this@FakeTimelines.enabled
                override val exhausted: Boolean get() = at >= shown.size

                override suspend fun next(count: Int): Result<List<TimelineEntry>> {
                    val pageOf = shown.drop(at).take(count)
                    at += pageOf.size
                    return Result.Success(pageOf)
                }
            },
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
class OperationsTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val session = FakeSession()
    private val shared = RepositoryViewState(Sample.location.commonDir, FakeUiState())
    private val timelines = FakeTimelines()

    private fun TestScope.opened(): OperationsViewModel {
        Dispatchers.setMain(dispatcher)
        val vm =
            ViewModelProvider.create(
                store,
                viewModelFactory {
                    initializer {
                        OperationsViewModel(Paths.get("/repo"), session, shared, timelines) {
                            Sample.now *
                                1000
                        }
                    }
                },
            )[OperationsViewModel::class]
        vm.onEvent(OperationsEvent.Opened)
        advanceUntilIdle()
        return vm
    }

    private fun done() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `resets, rebases and forced updates may drop commits, commits and checkouts don't`() {
        assertTrue(mayDropCommits("reset: moving to HEAD~3"))
        assertTrue(mayDropCommits("rebase (finish): returning to refs/heads/main"))
        assertTrue(mayDropCommits("fetch: forced-update"))
        assertFalse(mayDropCommits("commit: Fix login"))
        assertFalse(mayDropCommits("checkout: moving from main to feature"))
        assertFalse(mayDropCommits("pull: Fast-forward"))
    }

    @Test
    fun `the timeline pages in, refs fill the filter, and a filter reopens it over one ref`() = runTest(dispatcher) {
        timelines.entries = moves(450)
        val vm = opened()
        assertEquals(OperationsViewModel.PAGE, vm.state.value.entries.size)
        assertEquals(listOf("HEAD", "refs/heads/feature/login", "refs/heads/main"), vm.state.value.refs)
        vm.onEvent(OperationsEvent.NearEnd)
        advanceUntilIdle()
        assertEquals(400, vm.state.value.entries.size)
        assertFalse(vm.state.value.exhausted)
        vm.onEvent(OperationsEvent.NearEnd)
        advanceUntilIdle()
        assertTrue(vm.state.value.exhausted)
        vm.onEvent(OperationsEvent.FilterChosen("refs/heads/main"))
        advanceUntilIdle()
        assertEquals(listOf("refs/heads/main"), timelines.opened.last())
        assertTrue(vm.state.value.entries.all { "refs/heads/main" in it.refs })
        done()
    }

    @Test
    fun `selecting a move selects its commit, Mod+D compares before and after, Mod+C copies`() = runTest(dispatcher) {
        val vm = opened()
        val effects = mutableListOf<OperationsEffect>()
        val collector = launch { vm.effects.collect { effects += it } }
        vm.onEvent(OperationsEvent.Moved(1))
        advanceUntilIdle()
        val first = vm.state.value.entries[0]
        assertEquals(ViewSelection.Commit(first.newId), shared.selection.value)
        vm.onEvent(OperationsEvent.CompareMove)
        vm.onEvent(OperationsEvent.CopyHash)
        advanceUntilIdle()
        assertEquals(CenterView.Compare(first.oldId!!.hex, first.newId.hex), shared.center.value)
        assertEquals(listOf<OperationsEffect>(OperationsEffect.CopyToClipboard(first.newId.hex)), effects)
        collector.cancel()

        // A ref's creation has no "before": nothing to compare.
        vm.onEvent(OperationsEvent.Moved(Int.MAX_VALUE / 2))
        vm.onEvent(OperationsEvent.CompareMove)
        advanceUntilIdle()
        assertEquals(CenterView.Compare(first.oldId!!.hex, first.newId.hex), shared.center.value)
        done()
    }

    @Test
    fun `a ref moving reloads the first page and keeps the selected move`() = runTest(dispatcher) {
        val vm = opened()
        vm.onEvent(OperationsEvent.Selected(2))
        advanceUntilIdle()
        val kept = vm.state.value.selectedEntry
        timelines.entries = moves(1, Sample.now + 60).map { it.copy(time = Sample.now + 60) } + moves(5)
        session.state.value = Sample.snapshot(refs = Sample.refs.drop(1))
        advanceUntilIdle()
        assertEquals(6, vm.state.value.entries.size)
        assertEquals(kept, vm.state.value.selectedEntry)
        assertEquals(3, vm.state.value.selected)
        done()
    }

    @Test
    fun `rows are read as moves, and the list's keys reach the timeline`() = runDesktopComposeUiTest(1000, 260) {
        val events = mutableStateListOf<OperationsEvent>()
        val state =
            OperationsState(entries = moves(4), phase = OperationsState.Phase.Ready, selected = 0, now = Sample.now)
        setContent { GraftTheme(GraftThemeMode.LIGHT) { OperationsContent(state) { events += it } } }
        onNodeWithContentDescription(
            "HEAD and main moved from 3333333 to 2222222, 10 minutes ago, commit: Change 0",
        ).assertExists()
        onNodeWithContentDescription(
            "HEAD and main moved from 5555555 to 4444444, 30 minutes ago, reset: moving to HEAD~3, may have dropped commits",
        )
            .assertExists()
        onNodeWithContentDescription("HEAD created at 5555555, 40 minutes ago, commit: Change 3").assertExists()
        val list = onNodeWithContentDescription("Operations, 4 moves")
        list.requestFocus()
        list.performKeyInput {
            pressKey(Key.DirectionDown)
            withKeyDown(Key.CtrlLeft) { pressKey(Key.D) }
            withKeyDown(Key.CtrlLeft) { pressKey(Key.C) }
        }
        assertEquals(
            listOf(OperationsEvent.Moved(1), OperationsEvent.CompareMove, OperationsEvent.CopyHash),
            events.filterNot { it is OperationsEvent.NearEnd },
        )
    }

    @Test
    fun `screenshot, operations, light`() = runDesktopComposeUiTest(1000, 260) {
        val state =
            OperationsState(
                entries = moves(6),
                phase = OperationsState.Phase.Ready,
                selected = 1,
                exhausted = true,
                now = Sample.now,
            )
        setContent { GraftTheme(GraftThemeMode.LIGHT) { OperationsContent(state) {} } }
        onRoot().captureRoboImage("src/test/snapshots/operations-light.png")
    }

    @Test
    fun `screenshot, operations, dark, reflog off`() = runDesktopComposeUiTest(1000, 260) {
        val state =
            OperationsState(
                entries = moves(3),
                phase = OperationsState.Phase.Ready,
                enabled = false,
                exhausted = true,
                now = Sample.now,
            )
        setContent { GraftTheme(GraftThemeMode.DARK) { OperationsContent(state) {} } }
        onRoot().captureRoboImage("src/test/snapshots/operations-off-dark.png")
    }

    @Test
    fun `screenshot, operations, empty`() = runDesktopComposeUiTest(1000, 160) {
        setContent {
            GraftTheme(GraftThemeMode.LIGHT) {
                OperationsContent(OperationsState(phase = OperationsState.Phase.Ready)) {}
            }
        }
        onRoot().captureRoboImage("src/test/snapshots/operations-empty-light.png")
    }
}
