// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.presentation

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.srctool.graft.data.app.api.models.KnownRepository
import com.srctool.graft.data.app.api.models.SavedStatus
import com.srctool.graft.data.app.api.models.Workspace
import com.srctool.graft.feature.home.domain.api.models.OpenOutcome
import com.srctool.graft.feature.home.domain.api.models.ScannedRepository
import com.srctool.graft.feature.home.presentation.home.HomeDialog
import com.srctool.graft.feature.home.presentation.home.HomeEffect
import com.srctool.graft.feature.home.presentation.home.HomeEvent
import com.srctool.graft.feature.home.presentation.home.HomeState
import com.srctool.graft.feature.home.presentation.home.HomeView
import com.srctool.graft.feature.home.presentation.home.HomeViewModel
import com.srctool.graft.feature.home.presentation.home.SummaryState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    private val app = KnownRepository(path("app"), lastOpened = 5_000, pinned = false, missing = false)
    private val api = KnownRepository(path("api"), lastOpened = 9_000, pinned = false, missing = false)
    private val old = KnownRepository(path("old-thing"), lastOpened = 1_000, pinned = true, missing = true)
    private val known = FakeKnown(listOf(app, api, old))
    private val workspaces = FakeWorkspaces(listOf(Workspace("acme", "Acme", listOf(path("app"), path("old-thing")))))
    private val open = FakeOpen(known = known)
    private val scan = FakeScan(workspaces = workspaces)
    private val summaries = FakeSummaries(
        mapOf(
            path("app") to summary("main", dirty = true, ahead = 1),
            path("api") to summary("main", behind = 3),
        ),
    )

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun TestScope.opened(): HomeViewModel {
        val vm =
            ViewModelProvider.create(
                store,
                viewModelFactory {
                    initializer { HomeViewModel(open, scan, summaries, known, workspaces) { 100_000_000 } }
                },
            )[HomeViewModel::class]
        vm.onEvent(HomeEvent.Opened)
        advanceUntilIdle()
        return vm
    }

    private fun TestScope.send(vm: HomeViewModel, vararg events: HomeEvent) {
        events.forEach(vm::onEvent)
        advanceUntilIdle()
    }

    @Test
    fun `the list shows pinned first, then the most recently opened, and workspaces beside it`() = runTest(dispatcher) {
        val vm = opened()
        val s = vm.state.value
        assertEquals(HomeState.Phase.Ready, s.phase)
        assertEquals(listOf("old-thing", "api", "app"), s.rows.map { it.name })
        assertEquals(listOf("Acme"), s.workspaces.map { it.name })
        assertEquals(100_000, s.now)
    }

    @Test
    fun `a row's summary is read once when shown, and never for a missing repository`() = runTest(dispatcher) {
        val vm = opened()
        send(
            vm,
            HomeEvent.RowShown(path("app")),
            HomeEvent.RowShown(path("app")),
            HomeEvent.RowShown(path("old-thing")),
        )
        assertEquals(listOf(path("app")), summaries.asked)
        val loaded = assertIs<SummaryState.Loaded>(vm.state.value.summaries.getValue(path("app")))
        assertTrue(loaded.summary.dirty)
        send(vm, HomeEvent.RowShown(path("api")))
        assertEquals(
            3,
            (vm.state.value.summaries.getValue(path("api")) as SummaryState.Loaded).summary.upstream?.behind,
        )
    }

    @Test
    fun `search filters by name or path, and the keys move through what's left`() = runTest(dispatcher) {
        val vm = opened()
        send(vm, HomeEvent.QueryTyped("AP"))
        assertEquals(listOf("api", "app"), vm.state.value.rows.map { it.name })
        send(vm, HomeEvent.Moved(1), HomeEvent.Moved(1), HomeEvent.Moved(1))
        assertEquals(path("app").toString(), vm.state.value.focus)
        send(vm, HomeEvent.QueryTyped("/work/old"))
        assertEquals(listOf("old-thing"), vm.state.value.rows.map { it.name })
    }

    @Test
    fun `opening a folder hands its root to the host, and the list reloads`() = runTest(dispatcher) {
        val vm = opened()
        val effects = mutableListOf<HomeEffect>()
        val collector = launch { vm.effects.collect { effects += it } }
        send(vm, HomeEvent.OpenFolder(path("new")))
        assertEquals(listOf<HomeEffect>(HomeEffect.Open(path("new"))), effects)
        assertTrue(vm.state.value.repositories.any { it.path == path("new") }, "recorded, then the list was read again")
        collector.cancel()
    }

    @Test
    fun `a non-repository, a missing folder, and an untrusted one each get their dialog`() = runTest(dispatcher) {
        val untrusted = OpenOutcome.Untrusted(
            "/work/theirs",
            listOf("git", "config", "--global", "--add", "safe.directory", "/work/theirs"),
        )
        open.outcomes =
            mapOf(
                path("plain") to OpenOutcome.NotARepository(path("plain")),
                path("gone") to OpenOutcome.FolderMissing(path("gone")),
                path("theirs") to untrusted,
            )
        val vm = opened()
        send(vm, HomeEvent.OpenFolder(path("plain")))
        assertEquals(HomeDialog.NotARepository(path("plain")), vm.state.value.dialog)
        send(vm, HomeEvent.OpenFolder(path("gone")))
        assertEquals(HomeDialog.Problem(null, "missing:/work/gone"), vm.state.value.dialog)

        val effects = mutableListOf<HomeEffect>()
        val collector = launch { vm.effects.collect { effects += it } }
        send(vm, HomeEvent.OpenFolder(path("theirs")))
        assertEquals(HomeDialog.Trust(untrusted, path("theirs")), vm.state.value.dialog)
        send(vm, HomeEvent.TrustConfirmed)
        assertEquals(listOf(untrusted), open.trusted)
        assertEquals(listOf<HomeEffect>(HomeEffect.Open(path("theirs"))), effects)
        assertEquals(HomeDialog.None, vm.state.value.dialog)
        collector.cancel()
    }

    @Test
    fun `pinning, removing and locating change the saved list and show it`() = runTest(dispatcher) {
        val vm = opened()
        send(vm, HomeEvent.PinToggled(path("app")))
        assertTrue(vm.state.value.repositories.first { it.path == path("app") }.pinned)
        send(vm, HomeEvent.RemoveAsked(path("api")))
        assertEquals(HomeDialog.RemoveRepository(path("api")), vm.state.value.dialog)
        send(vm, HomeEvent.RemoveConfirmed)
        assertEquals(
            listOf(path("app"), path("old-thing")).sorted(),
            vm.state.value.repositories.map {
                it.path
            }.sorted(),
        )
        send(vm, HomeEvent.Located(path("old-thing"), path("moved")))
        assertEquals(false, vm.state.value.repositories.first { it.path == path("moved") }.missing)
    }

    @Test
    fun `a workspace opens as its own list, and Open all leaves out what's missing`() = runTest(dispatcher) {
        val vm = opened()
        val effects = mutableListOf<HomeEffect>()
        val collector = launch { vm.effects.collect { effects += it } }
        send(vm, HomeEvent.ViewChosen(HomeView.Workspace("acme")), HomeEvent.OpenAll)
        assertEquals(listOf("app", "old-thing"), vm.state.value.rows.map { it.name })
        assertEquals(listOf<HomeEffect>(HomeEffect.OpenAll(listOf(path("app")))), effects)
        collector.cancel()
    }

    @Test
    fun `workspaces are created, renamed, filled, emptied and deleted`() = runTest(dispatcher) {
        val vm = opened()
        send(vm, HomeEvent.DialogSet(HomeDialog.NewWorkspace("Side projects")), HomeEvent.NewWorkspaceConfirmed)
        val created = vm.state.value.workspaces.first { it.name == "Side projects" }
        assertEquals(HomeView.Workspace(created.id), vm.state.value.view)

        send(vm, HomeEvent.DialogSet(HomeDialog.RenameWorkspace(created.id, "Side")), HomeEvent.RenameConfirmed)
        assertEquals("Side", vm.state.value.workspaces.first { it.id == created.id }.name)

        send(
            vm,
            HomeEvent.DialogSet(HomeDialog.AddToWorkspace(created.id)),
            HomeEvent.AddToWorkspaceToggled(path("api")),
            HomeEvent.AddToWorkspaceConfirmed,
        )
        assertEquals(listOf(path("api")), vm.state.value.workspaces.first { it.id == created.id }.repositories)
        send(vm, HomeEvent.FolderAddedToWorkspace(created.id, path("fresh")))
        assertTrue(path("fresh") in vm.state.value.workspaces.first { it.id == created.id }.repositories)
        send(vm, HomeEvent.WorkspaceRepositoryRemoved(created.id, path("api")))
        assertEquals(listOf(path("fresh")), vm.state.value.workspaces.first { it.id == created.id }.repositories)

        send(
            vm,
            HomeEvent.DialogSet(HomeDialog.DeleteWorkspace(created.id, "Side")),
            HomeEvent.DeleteWorkspaceConfirmed,
        )
        assertEquals(HomeView.All, vm.state.value.view, "the deleted workspace's view falls back to All")
        assertEquals(listOf("Acme"), vm.state.value.workspaces.map { it.name })
    }

    @Test
    fun `a scan checks what Home lacks, and adds the checked ones, optionally as a workspace`() = runTest(dispatcher) {
        scan.found =
            listOf(
                ScannedRepository(path("app"), known = true),
                ScannedRepository(path("one"), false),
                ScannedRepository(path("two"), false),
            )
        val vm = opened()
        send(vm, HomeEvent.ScanFolderChosen(path("")))
        val dialog = assertIs<HomeDialog.Scan>(vm.state.value.dialog)
        assertEquals(setOf(path("one"), path("two")), dialog.selected)
        send(
            vm,
            HomeEvent.ScanToggled(path("two")),
            HomeEvent.DialogSet(dialog.copy(selected = setOf(path("one")), scanning = false, workspaceName = "Found")),
            HomeEvent.ScanConfirmed,
        )
        assertTrue(vm.state.value.repositories.any { it.path == path("one") })
        assertTrue(vm.state.value.repositories.none { it.path == path("two") })
        val workspace = vm.state.value.workspaces.first { it.name == "Found" }
        assertEquals(listOf(path("one")), workspace.repositories)
        assertEquals(HomeView.Workspace(workspace.id), vm.state.value.view)
    }

    @Test
    fun `a list from a newer Graft is read-only, and a refused write comes back as a problem`() = runTest(dispatcher) {
        val newer = SavedStatus.Newer(2)
        val read = opened()
        assertEquals(false, read.state.value.readOnly)
        store.clear()

        known.status = newer
        known.writeFails = true
        val vm = opened()
        assertTrue(vm.state.value.readOnly)
        send(vm, HomeEvent.PinToggled(path("app")))
        assertEquals(FakeFailure::class, (vm.state.value.dialog as HomeDialog.Problem).error!!::class)
    }
}
