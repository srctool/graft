// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.srctool.graft.core.ui.git.DiffMode
import com.srctool.graft.data.app.api.models.AppSettings
import com.srctool.graft.data.app.api.models.DiffStyle
import com.srctool.graft.data.git.api.models.FileStatus
import com.srctool.graft.feature.graph.presentation.branches.BranchesEvent
import com.srctool.graft.feature.graph.presentation.branches.BranchesState
import com.srctool.graft.feature.graph.presentation.branches.BranchesViewModel
import com.srctool.graft.feature.graph.presentation.branches.PanelRow
import com.srctool.graft.feature.graph.presentation.details.DetailsEvent
import com.srctool.graft.feature.graph.presentation.details.DetailsState
import com.srctool.graft.feature.graph.presentation.details.DetailsViewModel
import com.srctool.graft.feature.graph.presentation.details.FileRow
import com.srctool.graft.feature.graph.presentation.diff.DiffBody
import com.srctool.graft.feature.graph.presentation.diff.DiffScreenEvent
import com.srctool.graft.feature.graph.presentation.diff.DiffScreenViewModel
import com.srctool.graft.feature.graph.presentation.graph.FakeCommitRepository
import com.srctool.graft.feature.graph.presentation.graph.FakeFailure
import com.srctool.graft.feature.graph.presentation.graph.FakeSession
import com.srctool.graft.feature.graph.presentation.graph.FakeStashes
import com.srctool.graft.feature.graph.presentation.graph.FakeUiState
import com.srctool.graft.feature.graph.presentation.graph.Sample
import com.srctool.graft.feature.graph.presentation.graph.sampleFiles
import com.srctool.graft.feature.graph.presentation.graph.sampleStash
import com.srctool.graft.presentation.view.CenterView
import com.srctool.graft.presentation.view.RepositoryViewState
import com.srctool.graft.presentation.view.ViewSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The left panel, commit details and diff screen view models, linked by the tab's shared state as in the app. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreensViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val session = FakeSession()
    private val ui = FakeUiState()
    private val shared = RepositoryViewState(Sample.location.commonDir, ui)
    private val commits = FakeCommitRepository()
    private val stashes = FakeStashes(listOf(sampleStash))
    private val diffs = FakeDiffs()
    private val settings = FakeSettings()
    private val repo = Paths.get("/repo")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private inline fun <reified T : ViewModel> make(noinline create: () -> T): T =
        ViewModelProvider.create(store, viewModelFactory { initializer { create() } })[T::class]

    private fun TestScope.branches() = make {
        BranchesViewModel(repo, session, shared, stashes)
    }.also {
        it.onEvent(BranchesEvent.Opened)
        advanceUntilIdle()
    }

    private fun TestScope.details() =
        make { DetailsViewModel(repo, session, shared, commits) { Sample.now * 1000 } }.also {
            it.onEvent(DetailsEvent.Opened)
            advanceUntilIdle()
        }

    private fun TestScope.diff() = make {
        DiffScreenViewModel(repo, shared, diffs, settings)
    }.also {
        it.onEvent(DiffScreenEvent.Opened)
        advanceUntilIdle()
    }

    // Left panel

    @Test
    fun `the panel shows the session's refs and stashes, and Enter selects a ref's commit`() = runTest(dispatcher) {
        val vm = branches()
        val state = vm.state.value
        assertEquals(BranchesState.Phase.Ready, state.phase)
        val keys = state.rows.map { it.key }
        assertTrue("ref:refs/heads/main" in keys && "stash:${sampleStash.id.hex}" in keys, keys.toString())
        vm.onEvent(BranchesEvent.Activated("ref:refs/tags/v1.0"))
        advanceUntilIdle()
        assertEquals(ViewSelection.Commit(Sample.id(2)), shared.selection.value)
        vm.onEvent(BranchesEvent.Activated("stash:${sampleStash.id.hex}"))
        advanceUntilIdle()
        assertEquals(ViewSelection.Commit(sampleStash.id), shared.selection.value)
    }

    @Test
    fun `hide, solo and pin are saved, and hiding a soloed ref ends its solo`() = runTest(dispatcher) {
        val vm = branches()
        vm.onEvent(BranchesEvent.SoloToggled("refs/heads/feature/login"))
        vm.onEvent(BranchesEvent.PinToggled("refs/heads/main"))
        advanceUntilIdle()
        assertEquals(setOf("refs/heads/feature/login"), ui.value.soloRefs)
        assertEquals(setOf("refs/heads/main"), ui.value.pinnedRefs)
        vm.onEvent(BranchesEvent.HideToggled("refs/heads/feature/login"))
        advanceUntilIdle()
        assertEquals(setOf("refs/heads/feature/login"), ui.value.hiddenRefs)
        assertEquals(emptySet(), ui.value.soloRefs)
        val row = vm.state.value.rows.filterIsInstance<PanelRow.RefItem>().first {
            it.ref.name ==
                "refs/heads/feature/login"
        }
        assertTrue(row.hidden && !row.solo)
    }

    @Test
    fun `arrows move, left and right fold, and the refs' failure shows with Retry`() = runTest(dispatcher) {
        val vm = branches()
        assertEquals("section:LOCAL", vm.state.value.focus, "the first row has focus to start")
        vm.onEvent(BranchesEvent.Collapse)
        advanceUntilIdle()
        assertTrue(vm.state.value.rows.none { it.key == "ref:refs/heads/main" }, "Local folded")
        vm.onEvent(BranchesEvent.Expand)
        vm.onEvent(BranchesEvent.Moved(1))
        advanceUntilIdle()
        assertEquals("folder:LOCAL:feature", vm.state.value.focus)

        session.state.value =
            Sample.snapshot().copy(
                loaded = emptySet(),
                failures = mapOf(com.srctool.graft.presentation.session.SnapshotPart.REFS to FakeFailure("refs")),
            )
        advanceUntilIdle()
        assertEquals(BranchesState.Phase.Failed, vm.state.value.phase)
    }

    // Commit details

    @Test
    fun `details follow the selection, from nothing to the WIP row to a commit`() = runTest(dispatcher) {
        val vm = details()
        assertEquals(DetailsState.Phase.Nothing, vm.state.value.phase)
        shared.selection.value = ViewSelection.Wip
        advanceUntilIdle()
        assertEquals(DetailsState.Phase.Wip, vm.state.value.phase)
        assertEquals(4, vm.state.value.wipFiles)
        shared.selection.value = ViewSelection.Commit(Sample.id(5))
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(DetailsState.Phase.Loaded, state.phase)
        assertEquals("Merge feature/login", state.details?.subject)
        assertEquals(
            listOf("folder:docs", "folder:src/main/kotlin/login", "file:src/main/kotlin/login/Backoff.kt"),
            state.rows.take(4).map { it.key }.filter { it != "file:docs/logo.png" },
        )
    }

    @Test
    fun `a file opens its diff, a parent selects that commit, and failures can be retried`() = runTest(dispatcher) {
        val vm = details()
        shared.selection.value = ViewSelection.Commit(Sample.id(5))
        advanceUntilIdle()
        vm.onEvent(DetailsEvent.Activated("file:src/main/kotlin/login/Login.kt"))
        advanceUntilIdle()
        assertEquals(CenterView.Diff(Sample.id(5), "src/main/kotlin/login/Login.kt"), shared.center.value)
        vm.onEvent(DetailsEvent.ParentChosen(1))
        advanceUntilIdle()
        assertEquals(ViewSelection.Commit(Sample.id(3)), shared.selection.value)

        commits.detailsFailure = FakeFailure("gone")
        shared.selection.value = ViewSelection.Commit(Sample.id(4))
        advanceUntilIdle()
        assertEquals(DetailsState.Phase.Failed, vm.state.value.phase)
        commits.detailsFailure = null
        vm.onEvent(DetailsEvent.Retry)
        advanceUntilIdle()
        assertEquals(DetailsState.Phase.Loaded, vm.state.value.phase)
    }

    @Test
    fun `the list shows whole paths and folders fold`() = runTest(dispatcher) {
        val vm = details()
        shared.selection.value = ViewSelection.Commit(Sample.id(5))
        advanceUntilIdle()
        vm.onEvent(DetailsEvent.Activated("folder:src/main/kotlin/login"))
        advanceUntilIdle()
        assertTrue(vm.state.value.rows.none { it is FileRow.File && it.file.path.startsWith("src/") })
        vm.onEvent(DetailsEvent.TreeChosen(false))
        advanceUntilIdle()
        assertEquals(sampleFiles.map { "file:${it.path}" }, vm.state.value.rows.map { it.key })
    }

    // Diff screen

    @Test
    fun `the diff shows the center's file, steps through files, and goes back to the graph`() = runTest(dispatcher) {
        settings.value = AppSettings(diffStyle = DiffStyle.SIDE_BY_SIDE)
        val vm = diff()
        assertEquals(DiffMode.SIDE_BY_SIDE, vm.state.value.mode)
        shared.center.value = CenterView.Diff(Sample.id(5), "src/main/kotlin/login/Login.kt")
        advanceUntilIdle()
        val text = assertIs<DiffBody.Text>(vm.state.value.body)
        assertEquals("@@ -10,3 +10,3 @@ class Login", text.document.hunks.single().header)
        assertEquals("Fix login timeout", vm.state.value.subject)

        vm.onEvent(DiffScreenEvent.StepFile(+1))
        advanceUntilIdle()
        assertEquals(1, vm.state.value.index)
        vm.onEvent(DiffScreenEvent.StepFile(-1))
        vm.onEvent(DiffScreenEvent.StepFile(-1))
        advanceUntilIdle()
        assertEquals(0, vm.state.value.index, "no file before the first")
        vm.onEvent(DiffScreenEvent.Back)
        advanceUntilIdle()
        assertEquals(CenterView.Graph, shared.center.value)
    }

    @Test
    fun `mode and whitespace are app settings, and whitespace reaches git`() = runTest(dispatcher) {
        val vm = diff()
        shared.center.value = CenterView.Diff(Sample.id(5), "src/main/kotlin/login/Login.kt")
        advanceUntilIdle()
        vm.onEvent(DiffScreenEvent.ModeToggled)
        vm.onEvent(DiffScreenEvent.WhitespaceToggled)
        advanceUntilIdle()
        assertEquals(DiffStyle.SIDE_BY_SIDE, settings.value.diffStyle)
        assertTrue(settings.value.ignoreWhitespace)
        assertTrue(diffs.optionsSeen.last().ignoreWhitespace)
    }

    @Test
    fun `binary files show their sizes and images, big files wait for Show anyway`() = runTest(dispatcher) {
        diffs.image = byteArrayOf(1, 2, 3)
        val vm = diff()
        shared.center.value = CenterView.Diff(Sample.id(5), "docs/logo.png")
        advanceUntilIdle()
        val binary = assertIs<DiffBody.Binary>(vm.state.value.body)
        assertEquals(100L, binary.newSize)
        assertTrue(binary.images != null)

        diffs.sizes = mapOf(sampleFiles[0].newObject!! to 5_000_000L)
        shared.center.value = CenterView.Diff(Sample.id(5), "src/main/kotlin/login/Login.kt")
        advanceUntilIdle()
        assertEquals(DiffBody.TooLarge(5_000_000L), vm.state.value.body)
        vm.onEvent(DiffScreenEvent.ShowAnyway)
        advanceUntilIdle()
        assertIs<DiffBody.Text>(vm.state.value.body)
    }

    @Test
    fun `a rename without changes says so`() = runTest(dispatcher) {
        diffs.files =
            sampleFiles.map { if (it.status == FileStatus.RENAMED) it.copy(additions = 0, deletions = 0) else it }
        val vm = diff()
        shared.center.value = CenterView.Diff(Sample.id(5), "src/main/kotlin/login/Retry.kt")
        advanceUntilIdle()
        assertEquals(DiffBody.Renamed("src/main/kotlin/login/Old.kt"), vm.state.value.body)
        assertNull(vm.state.value.body.let { (it as? DiffBody.Failed)?.error })
    }
}
