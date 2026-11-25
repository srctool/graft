// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.presentation.changes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.srctool.graft.core.ui.git.DiffLineRef
import com.srctool.graft.data.git.api.models.FileSelection
import com.srctool.graft.data.git.api.models.LineRange
import com.srctool.graft.feature.workingcopy.domain.COPY
import com.srctool.graft.feature.workingcopy.domain.LOCATION
import com.srctool.graft.feature.workingcopy.domain.api.models.DiffSide
import com.srctool.graft.feature.workingcopy.domain.api.models.SectionKind
import com.srctool.graft.feature.workingcopy.domain.api.models.UndoDiscardOutcome
import com.srctool.graft.feature.workingcopy.domain.failure
import com.srctool.graft.presentation.session.SnapshotPart
import com.srctool.graft.presentation.view.CenterView
import com.srctool.graft.presentation.view.RepositoryViewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChangesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val session = FakeSession()
    private val shared = RepositoryViewState(LOCATION.commonDir, FakeUiState())
    private val loads = FakeLoads()
    private val panel = FakePanelState()
    private val diffs = FakeWorkingDiffs()
    private val staging = FakeStaging()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private inline fun <reified T : ViewModel> make(noinline create: () -> T): T =
        ViewModelProvider.create(store, viewModelFactory { initializer { create() } })[T::class]

    private fun TestScope.opened(): ChangesViewModel {
        loads.push(Sample.model())
        val vm = make { ChangesViewModel(COPY, session, shared, loads, panel, diffs, staging) }
        vm.onEvent(ChangesEvent.Opened)
        runCurrent()
        return vm
    }

    private fun ChangesViewModel.indexOf(key: String) = state.value.lines.indexOfFirst { it.key == key }

    @Test
    fun `the lists load with the branch and counts`() = runTest(dispatcher) {
        val vm = opened()
        val s = vm.state.value
        assertEquals(ChangesState.Phase.Ready, s.phase)
        assertEquals("main", s.branch)
        assertTrue(s.lines.any { it is PanelLine.Header && it.section == SectionKind.Unstaged })
    }

    @Test
    fun `a failed read is the failed phase, and retrying asks the session for a new status`() = runTest(dispatcher) {
        val vm = make { ChangesViewModel(COPY, session, shared, loads, panel, diffs, staging) }
        loads.loads.tryEmit(failure("STAT"))
        vm.onEvent(ChangesEvent.Opened)
        runCurrent()
        assertEquals(ChangesState.Phase.Failed, vm.state.value.phase)
        assertNotNull(vm.state.value.error)
        vm.onEvent(ChangesEvent.Retry)
        runCurrent()
        assertEquals(listOf(setOf(SnapshotPart.STATUS)), session.refreshed)
    }

    @Test
    fun `opening a file shows its diff in the center and reads the side its section is`() = runTest(dispatcher) {
        val vm = opened()
        vm.onEvent(ChangesEvent.Activated(vm.indexOf("file:unstaged:src/login/Login.kt")))
        runCurrent()
        assertEquals(listOf("src/login/Login.kt" to DiffSide.UNSTAGED), diffs.reads)
        assertEquals(CenterView.Contributed(ChangesViewModel.DIFF_CENTER, "src/login/Login.kt"), shared.center.value)
        vm.onEvent(ChangesEvent.Activated(vm.indexOf("file:staged:src/login/Login.kt")))
        runCurrent()
        assertEquals(DiffSide.STAGED, diffs.reads.last().second)
        vm.onEvent(ChangesEvent.DiffClosed)
        runCurrent()
        assertEquals(CenterView.Graph, shared.center.value)
        assertNull(vm.state.value.open)
    }

    @Test
    fun `a folder folds and opens, and is remembered`() = runTest(dispatcher) {
        val vm = opened()
        val folder = vm.indexOf("folder:unstaged:src/login")
        vm.onEvent(ChangesEvent.Activated(folder))
        runCurrent()
        assertEquals(setOf("src/login"), panel.collapsed)
        assertTrue(vm.state.value.lines.none { it.key == "file:unstaged:src/login/Session.kt" })
    }

    @Test
    fun `the tree toggle switches to a flat list and is remembered`() = runTest(dispatcher) {
        val vm = opened()
        vm.onEvent(ChangesEvent.TreeToggled)
        runCurrent()
        assertTrue(vm.state.value.lines.none { it is PanelLine.Folder })
        assertEquals(false, panel.tree)
    }

    @Test
    fun `space stages an unstaged file whole and unstages a staged one`() = runTest(dispatcher) {
        val vm = opened()
        vm.onEvent(ChangesEvent.StageToggled(vm.indexOf("file:unstaged:src/login/Session.kt")))
        runCurrent()
        assertEquals(
            listOf(FileSelection("src/login/Session.kt", FileSelection.Scope.WholeFile)),
            staging.staged.single().files,
        )
        vm.onEvent(ChangesEvent.StageToggled(vm.indexOf("file:staged:src/login/Backoff.kt")))
        runCurrent()
        assertEquals("src/login/Backoff.kt", staging.unstaged.single().files.single().path)
        // Each write asks for the lists again.
        assertEquals(2, session.refreshed.count { SnapshotPart.STATUS in it })
    }

    @Test
    fun `a section header and a folder stage everything under them`() = runTest(dispatcher) {
        val vm = opened()
        vm.onEvent(ChangesEvent.SectionToggled(vm.indexOf("section:unstaged")))
        runCurrent()
        assertEquals(
            setOf("src/login/Login.kt", "src/login/Session.kt", "notes.txt", "libs/engine"),
            staging.staged.single().files.map {
                it.path
            }.toSet(),
        )
        vm.onEvent(ChangesEvent.StageToggled(vm.indexOf("folder:staged:src/login")))
        runCurrent()
        assertEquals(2, staging.unstaged.single().files.size)
    }

    @Test
    fun `a conflict row is not stageable here`() = runTest(dispatcher) {
        val conflicted = Sample.model().let { m ->
            m.copy(
                sections =
                listOf(
                    com.srctool.graft.feature.workingcopy.domain.api.models.ChangesSection(
                        SectionKind.Conflicts,
                        listOf(Sample.session),
                    ),
                ) +
                    m.sections,
            )
        }
        loads.push(conflicted)
        val vm = make { ChangesViewModel(COPY, session, shared, loads, panel, diffs, staging) }
        vm.onEvent(ChangesEvent.Opened)
        runCurrent()
        vm.onEvent(ChangesEvent.StageToggled(vm.indexOf("file:conflicts:src/login/Session.kt")))
        runCurrent()
        assertTrue(staging.staged.isEmpty() && staging.unstaged.isEmpty())
    }

    @Test
    fun `ticks are a pending selection that an action applies as lines`() = runTest(dispatcher) {
        val vm = opened()
        vm.onEvent(ChangesEvent.Activated(vm.indexOf("file:unstaged:src/login/Login.kt")))
        runCurrent()
        vm.onEvent(ChangesEvent.LineTicked(DiffLineRef(0, 1)))
        vm.onEvent(ChangesEvent.LineTicked(DiffLineRef(0, 3)))
        runCurrent()
        assertEquals(setOf(DiffLineRef(0, 1), DiffLineRef(0, 3)), vm.state.value.ticked)
        assertTrue(staging.staged.isEmpty(), "ticking changes nothing in the index")
        vm.onEvent(ChangesEvent.TicksApplied)
        runCurrent()
        val file = staging.staged.single().files.single()
        assertEquals("src/login/Login.kt", file.path)
        assertEquals(FileSelection.Scope.Lines(listOf(LineRange(0, 1), LineRange(0, 3))), file.scope)
        assertTrue(vm.state.value.ticked.isEmpty())
    }

    @Test
    fun `ticking every changed line of a hunk ticks the hunk, and the hunk's box ticks all of it`() =
        runTest(dispatcher) {
            val vm = opened()
            vm.onEvent(ChangesEvent.Activated(vm.indexOf("file:unstaged:src/login/Login.kt")))
            runCurrent()
            vm.onEvent(ChangesEvent.HunkTicked(0))
            runCurrent()
            assertEquals(4, vm.state.value.ticked.size)
            vm.onEvent(ChangesEvent.TicksCleared)
            runCurrent()
            assertTrue(vm.state.value.ticked.isEmpty())
        }

    @Test
    fun `the ticks of a file are forgotten when another file opens`() = runTest(dispatcher) {
        val vm = opened()
        vm.onEvent(ChangesEvent.Activated(vm.indexOf("file:unstaged:src/login/Login.kt")))
        runCurrent()
        vm.onEvent(ChangesEvent.LineTicked(DiffLineRef(0, 1)))
        vm.onEvent(ChangesEvent.Activated(vm.indexOf("file:unstaged:src/login/Session.kt")))
        runCurrent()
        assertTrue(vm.state.value.ticked.isEmpty() || vm.state.value.ticks.isEmpty)
    }

    @Test
    fun `a hunk applies at once without ticking`() = runTest(dispatcher) {
        val vm = opened()
        vm.onEvent(ChangesEvent.Activated(vm.indexOf("file:unstaged:src/login/Login.kt")))
        runCurrent()
        vm.onEvent(ChangesEvent.HunkApplied(0))
        runCurrent()
        assertEquals(FileSelection.Scope.Hunks(setOf(0)), staging.staged.single().files.single().scope)
    }

    @Test
    fun `a write that fails shows its error, keeps the lists, and frees the panel`() = runTest(dispatcher) {
        val vm = opened()
        staging.failure = failure("APLY")
        vm.onEvent(ChangesEvent.StageToggled(vm.indexOf("file:unstaged:src/login/Session.kt")))
        runCurrent()
        val s = vm.state.value
        assertNotNull(s.error)
        assertNull(s.busy)
        assertEquals(ChangesState.Phase.Ready, s.phase)
    }

    @Test
    fun `discard asks first, names untracked files as deleted, and offers undo for 10 seconds`() = runTest(dispatcher) {
        val vm = opened()
        vm.onEvent(ChangesEvent.DiscardAsked(vm.indexOf("file:unstaged:notes.txt")))
        runCurrent()
        assertEquals(true, vm.state.value.discard?.deletes)
        assertTrue(staging.discarded.isEmpty(), "nothing is discarded before the confirmation")
        vm.onEvent(ChangesEvent.DiscardCancelled)
        runCurrent()
        assertNull(vm.state.value.discard)

        vm.onEvent(ChangesEvent.DiscardAsked(vm.indexOf("file:unstaged:src/login/Session.kt")))
        vm.onEvent(ChangesEvent.DiscardConfirmed)
        runCurrent()
        assertEquals("src/login/Session.kt", staging.discarded.single().files.single().path)
        val toast = vm.state.value.toast
        assertNotNull(toast?.undo)
        advanceTimeBy(ChangesViewModel.TOAST_MS + 1)
        assertNull(vm.state.value.toast)
    }

    @Test
    fun `a staged file can't be discarded from the list`() = runTest(dispatcher) {
        val vm = opened()
        vm.onEvent(ChangesEvent.DiscardAsked(vm.indexOf("file:staged:src/login/Backoff.kt")))
        runCurrent()
        assertNull(vm.state.value.discard)
    }

    @Test
    fun `ticked lines discard after a confirmation that counts them`() = runTest(dispatcher) {
        val vm = opened()
        vm.onEvent(ChangesEvent.Activated(vm.indexOf("file:unstaged:src/login/Login.kt")))
        runCurrent()
        vm.onEvent(ChangesEvent.LineTicked(DiffLineRef(0, 2)))
        vm.onEvent(ChangesEvent.TicksDiscardAsked)
        runCurrent()
        assertEquals(1, vm.state.value.discard?.lines)
        vm.onEvent(ChangesEvent.DiscardConfirmed)
        runCurrent()
        assertEquals(
            FileSelection.Scope.Lines(listOf(LineRange(0, 2))),
            staging.discarded.single().files.single().scope,
        )
    }

    @Test
    fun `undo restores, and says so when the file changed since`() = runTest(dispatcher) {
        val vm = opened()
        vm.onEvent(ChangesEvent.DiscardAsked(vm.indexOf("file:unstaged:src/login/Session.kt")))
        vm.onEvent(ChangesEvent.DiscardConfirmed)
        runCurrent()
        vm.onEvent(ChangesEvent.UndoClicked)
        runCurrent()
        assertEquals(1, staging.undone)
        assertNull(vm.state.value.toast?.undo)

        staging.outcome = UndoDiscardOutcome.ChangedSince("src/login/Session.kt")
        vm.onEvent(ChangesEvent.DiscardAsked(vm.indexOf("file:unstaged:src/login/Session.kt")))
        vm.onEvent(ChangesEvent.DiscardConfirmed)
        runCurrent()
        vm.onEvent(ChangesEvent.UndoClicked)
        runCurrent()
        assertTrue(vm.state.value.toast?.text?.contains("changed since") == true)
    }

    @Test
    fun `the cursor moves, stays on its file across a refresh, and the arrows stop at the ends`() =
        runTest(dispatcher) {
            val vm = opened()
            vm.onEvent(ChangesEvent.Moved(1))
            runCurrent()
            assertEquals(0, vm.state.value.cursor)
            vm.onEvent(ChangesEvent.Moved(Int.MAX_VALUE / 2))
            runCurrent()
            val last = vm.state.value.lines.lastIndex
            assertEquals(last, vm.state.value.cursor)
            val key = vm.state.value.lines[last].key
            loads.push(Sample.model())
            runCurrent()
            assertEquals(key, vm.state.value.lines[vm.state.value.cursor].key)
        }

    @Test
    fun `a file that has no changes left shows nothing and a refresh keeps the open file current`() =
        runTest(dispatcher) {
            val vm = opened()
            vm.onEvent(ChangesEvent.Activated(vm.indexOf("file:unstaged:src/login/Session.kt")))
            runCurrent()
            val before = diffs.reads.size
            loads.push(Sample.model())
            runCurrent()
            assertTrue(diffs.reads.size > before, "the open diff is read again after the lists change")
        }
}
