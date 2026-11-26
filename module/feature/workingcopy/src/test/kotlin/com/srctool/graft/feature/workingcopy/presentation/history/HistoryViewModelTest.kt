// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.presentation.history

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.srctool.graft.feature.workingcopy.domain.COPY
import com.srctool.graft.feature.workingcopy.domain.LOCATION
import com.srctool.graft.feature.workingcopy.domain.api.models.HistoryDiff
import com.srctool.graft.feature.workingcopy.domain.api.models.RestoreOutcome
import com.srctool.graft.feature.workingcopy.domain.api.models.UndoRestoreOutcome
import com.srctool.graft.feature.workingcopy.domain.failure
import com.srctool.graft.feature.workingcopy.presentation.changes.FakeSession
import com.srctool.graft.feature.workingcopy.presentation.changes.FakeUiState
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
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val LOGIN = "src/login/Login.kt"

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val session = FakeSession()
    private val shared = RepositoryViewState(LOCATION.commonDir, FakeUiState())
    private val history = FakeLocalHistory()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun TestScope.shown(path: String? = null): HistoryViewModel {
        val factory = viewModelFactory {
            initializer {
                HistoryViewModel(COPY, session, shared, history, HistoryTexts.English(ZoneOffset.UTC))
            }
        }
        val vm = ViewModelProvider.create(store, factory)[HistoryViewModel::class]
        vm.send(this, HistoryEvent.Shown(path))
        return vm
    }

    private fun HistoryViewModel.send(scope: TestScope, event: HistoryEvent) {
        onEvent(event)
        scope.runCurrent()
    }

    @Test
    fun `the newest snapshot and its first file are shown, compared with now`() = runTest(dispatcher) {
        val s = shown().state.value
        assertEquals(HistoryState.Phase.Ready, s.phase)
        assertEquals(HistorySample.newest.id, s.selected)
        assertEquals(HistorySample.files, s.files)
        assertEquals(LOGIN, s.file)
        assertIs<HistoryBody.Ready>(s.body)
        assertTrue(s.canRestoreFile)
        assertEquals("diff 3 $LOGIN", history.calls.single())
    }

    @Test
    fun `a failed read is the failed phase, and Retry reads again`() = runTest(dispatcher) {
        history.failure = failure("LHST")
        val vm = shown()
        assertEquals(HistoryState.Phase.Failed, vm.state.value.phase)
        assertNotNull(vm.state.value.error)
        history.failure = null
        vm.send(this, HistoryEvent.Retry)
        assertEquals(HistoryState.Phase.Ready, vm.state.value.phase)
    }

    @Test
    fun `no snapshots is an empty history with nothing selected`() = runTest(dispatcher) {
        history.snapshots = emptyList()
        val s = shown().state.value
        assertTrue(s.entries.isEmpty())
        assertNull(s.selected)
        assertNull(s.body)
    }

    @Test
    fun `moving through snapshots and files reads each diff`() = runTest(dispatcher) {
        val vm = shown()
        vm.send(this, HistoryEvent.SnapshotMoved(1))
        assertEquals(HistorySample.middle.id, vm.state.value.selected)
        vm.send(this, HistoryEvent.FileMoved(1))
        assertEquals("notes.txt", vm.state.value.file)
        assertEquals("diff 2 notes.txt", history.calls.last())
        assertFalse(vm.state.value.canRestoreFile, "the snapshot deleted the file, so it has nothing to put back")
    }

    @Test
    fun `one file's history marks the snapshot that deleted it`() = runTest(dispatcher) {
        val vm = shown(LOGIN)
        val s = vm.state.value
        assertEquals(listOf(true, false), s.entries.map { it.deleted })
        assertEquals(LOGIN, s.file)
        assertFalse(s.canRestoreFile)
        vm.send(this, HistoryEvent.SnapshotMoved(1))
        assertTrue(vm.state.value.canRestoreFile)
    }

    @Test
    fun `All files leaves the file's history for the whole one`() = runTest(dispatcher) {
        val vm = shown(LOGIN)
        vm.send(this, HistoryEvent.AllFilesAsked)
        assertNull(vm.state.value.path)
        assertEquals(CenterView.Contributed(HistoryViewModel.CENTER, ""), shared.center.value)
    }

    @Test
    fun `a search shows its snapshots and selects the matching file, and clearing brings all back`() =
        runTest(dispatcher) {
            val vm = shown()
            vm.send(this, HistoryEvent.QueryTyped(" login "))
            vm.send(this, HistoryEvent.SearchRun)
            val s = vm.state.value
            assertEquals(listOf(HistorySample.middle.id), s.entries.map { it.snapshot.id })
            assertEquals(LOGIN, s.file)
            assertTrue("search PATHS login" in history.calls)
            vm.send(this, HistoryEvent.SearchCleared)
            assertEquals(3, vm.state.value.entries.size)
        }

    @Test
    fun `restoring a file says so and offers Undo for ten seconds`() = runTest(dispatcher) {
        val vm = shown()
        vm.send(this, HistoryEvent.FileRestored)
        assertTrue("restoreFile 3 $LOGIN" in history.calls)
        val toast = assertNotNull(vm.state.value.toast)
        assertEquals("Restored Login.kt to 14:02.", toast.text)
        assertNotNull(toast.undo)
        assertTrue(session.refreshed.isNotEmpty())
        advanceTimeBy(HistoryViewModel.TOAST_MS + 1)
        runCurrent()
        assertNull(vm.state.value.toast)
    }

    @Test
    fun `ticked hunks and a hunk's own action restore those hunks`() = runTest(dispatcher) {
        val vm = shown()
        vm.send(this, HistoryEvent.HunkTicked(0))
        vm.send(this, HistoryEvent.TickedRestored)
        vm.send(this, HistoryEvent.HunkRestored(1))
        assertEquals(
            listOf("restoreHunks 3 $LOGIN [0]", "restoreHunks 3 $LOGIN [1]"),
            history.calls.filter { it.startsWith("restoreHunks") },
        )
        assertTrue(vm.state.value.ticks.isEmpty())
    }

    @Test
    fun `restoring everything names what it removed`() = runTest(dispatcher) {
        val vm = shown()
        vm.send(this, HistoryEvent.AllRestored)
        assertEquals("Restored to 14:02. 2 newer files removed.", vm.state.value.toast?.text)
    }

    @Test
    fun `a file the history can't hold is reported and nothing offers Undo`() = runTest(dispatcher) {
        history.outcome = RestoreOutcome.Unprotected(LOGIN)
        val vm = shown()
        vm.send(this, HistoryEvent.FileRestored)
        val toast = assertNotNull(vm.state.value.toast)
        assertTrue(toast.text.startsWith("Nothing restored"))
        assertNull(toast.undo)
    }

    @Test
    fun `Undo reports a file edited since`() = runTest(dispatcher) {
        history.undoOutcome = UndoRestoreOutcome.ChangedSince(LOGIN)
        val vm = shown()
        vm.send(this, HistoryEvent.FileRestored)
        vm.send(this, HistoryEvent.UndoClicked)
        assertTrue("undo" in history.calls)
        assertEquals("$LOGIN changed since. Restore from Local History instead.", vm.state.value.toast?.text)
    }

    @Test
    fun `a deleted file is listed, shown and restored from the last snapshot that had it`() = runTest(dispatcher) {
        val vm = shown()
        vm.send(this, HistoryEvent.ViewChosen(HistoryView.DELETED))
        assertEquals("notes.txt", vm.state.value.file)
        assertEquals(HistorySample.middle.id, vm.state.value.selected)
        assertTrue(vm.state.value.canRestoreFile)
        vm.send(this, HistoryEvent.FileRestored)
        assertTrue("restoreFile 2 notes.txt" in history.calls)
    }

    @Test
    fun `snapshot now takes the label, and says when nothing changed`() = runTest(dispatcher) {
        history.created = false
        val vm = shown()
        vm.send(this, HistoryEvent.SnapshotAsked)
        assertEquals(HistoryDialog.SnapshotNow, vm.state.value.dialog)
        vm.send(this, HistoryEvent.SnapshotConfirmed("before refactor"))
        assertNull(vm.state.value.dialog)
        assertTrue("snapshot before refactor" in history.calls)
        assertEquals("Nothing changed since 14:02.", vm.state.value.toast?.text)
    }

    @Test
    fun `removing a file from the history asks first, and cancelling does nothing`() = runTest(dispatcher) {
        val vm = shown(LOGIN)
        vm.send(this, HistoryEvent.PurgeAsked)
        assertEquals(HistoryDialog.Purge(LOGIN), vm.state.value.dialog)
        vm.send(this, HistoryEvent.DialogCancelled)
        assertFalse("purge $LOGIN" in history.calls)
        vm.send(this, HistoryEvent.PurgeAsked)
        vm.send(this, HistoryEvent.PurgeConfirmed)
        assertTrue("purge $LOGIN" in history.calls)
        assertEquals("Removed Login.kt from 3 snapshots.", vm.state.value.toast?.text)
    }

    @Test
    fun `a finished refresh reads the lists again and keeps the selection`() = runTest(dispatcher) {
        val vm = shown()
        vm.send(this, HistoryEvent.SnapshotMoved(1))
        history.snapshots = HistorySample.snapshots.drop(1)
        session.state.value = session.state.value.copy(refreshing = setOf(SnapshotPart.REFS))
        runCurrent()
        session.state.value = session.state.value.copy(refreshing = emptySet())
        runCurrent()
        assertEquals(2, vm.state.value.entries.size)
        assertEquals(HistorySample.middle.id, vm.state.value.selected)
    }

    @Test
    fun `a large file's diff is read when asked for`() = runTest(dispatcher) {
        history.diff = HistoryDiff.TooLarge(5_000_000)
        val vm = shown()
        vm.send(this, HistoryEvent.ShowAnyway)
        assertEquals("diff 3 $LOGIN anyway", history.calls.last())
    }

    @Test
    fun `closing goes back from the center`() = runTest(dispatcher) {
        shared.center.value = CenterView.Contributed(HistoryViewModel.CENTER, "")
        val vm = shown()
        vm.send(this, HistoryEvent.Closed)
        assertEquals(CenterView.Graph, shared.center.value)
    }
}
