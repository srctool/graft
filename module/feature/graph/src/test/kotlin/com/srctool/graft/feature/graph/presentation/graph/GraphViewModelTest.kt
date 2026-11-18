// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.graph

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.srctool.graft.data.app.api.models.GraphAuthorKind
import com.srctool.graft.data.app.api.models.GraphBranches
import com.srctool.graft.data.app.api.models.GraphViewState
import com.srctool.graft.data.app.api.models.RepositoryUiState
import com.srctool.graft.data.git.api.models.LogScope
import com.srctool.graft.data.git.api.models.Ref
import com.srctool.graft.feature.graph.domain.implementation.usecases.GitLoadGraphUseCase
import com.srctool.graft.feature.graph.domain.implementation.usecases.GitSearchGraphUseCase
import com.srctool.graft.feature.graph.domain.implementation.usecases.LaneLayout
import com.srctool.graft.feature.graph.presentation.graph.GraphState.Phase
import com.srctool.graft.presentation.view.RepositoryViewState
import com.srctool.graft.presentation.view.ViewSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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

/** The graph screen's view model over the real graph use cases, with fake git, session and state.json. */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val session = FakeSession()
    private val commits = FakeCommitRepository()
    private val ui = FakeUiState()
    private val stashes = FakeStashes()
    private val shared by lazy { RepositoryViewState(Sample.location.commonDir, ui) }

    /** Each read of the clock moves it on by this much: zero is a fast load, SLOW_NANOS a slow one. */
    private var tick = 0L
    private var nanos = 0L

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel(): GraphViewModel = ViewModelProvider.create(
        store,
        viewModelFactory {
            initializer {
                GraphViewModel(
                    repository = Paths.get("/repo"),
                    session = session,
                    loadGraph = GitLoadGraphUseCase(commits, ::LaneLayout, dispatcher),
                    searchGraph = GitSearchGraphUseCase(commits, dispatcher),
                    commits = commits,
                    stashes = stashes,
                    shared = shared,
                    clock = { Sample.now * 1000 },
                    nanos = { nanos.also { nanos += tick } },
                )
            }
        },
    )[GraphViewModel::class]

    private fun TestScope.opened(): GraphViewModel = viewModel().also {
        it.onEvent(GraphEvent.Opened)
        advanceUntilIdle()
    }

    private fun GraphViewModel.selectedRow(): Int? = (state.value.selection as? Selection.Row)?.index

    @Test
    fun `opening loads every branch, with refs, HEAD and the WIP row from the session`() = runTest(dispatcher) {
        val vm = opened()
        val state = vm.state.value
        assertEquals(Phase.Loaded, state.phase)
        assertEquals(5, state.rowCount)
        assertEquals(listOf<LogScope>(LogScope.All()), commits.scopes)
        assertEquals(4, state.wipFiles)
        assertEquals(Sample.id(5), state.head)
        val main = state.refs.getValue(Sample.id(5)).single()
        assertEquals("main", main.name)
        assertEquals("origin/main", main.mergedRemote)
        assertEquals(Sample.now, state.now)
    }

    @Test
    fun `saved choices are read first and pruned to refs that exist`() = runTest(dispatcher) {
        ui.value =
            RepositoryUiState(
                hiddenRefs = setOf("refs/heads/feature/login", "refs/heads/deleted"),
                graph = GraphViewState(firstParent = true),
            )
        val vm = opened()
        assertEquals(listOf<LogScope>(LogScope.All(setOf("refs/heads/feature/login"))), commits.scopes)
        assertEquals(listOf(true), commits.firstParents)
        assertTrue(vm.state.value.view.firstParent)
    }

    @Test
    fun `a new repository with no commits is empty without running git log`() = runTest(dispatcher) {
        session.state.value = Sample.snapshot(refs = emptyList(), dirty = null, head = null)
        val vm = opened()
        assertEquals(Phase.Empty, vm.state.value.phase)
        assertTrue(commits.scopes.isEmpty())
    }

    @Test
    fun `filters that git applies are saved and reload, the author filter only dims`() = runTest(dispatcher) {
        val vm = opened()
        vm.onEvent(GraphEvent.BranchesChosen(GraphBranches.CURRENT))
        advanceUntilIdle()
        assertEquals(LogScope.Only(setOf("HEAD")), commits.scopes.last())
        // Two toggles in a row both count: each applies to the choices as they are when it runs.
        vm.onEvent(GraphEvent.HideRemotesToggled)
        vm.onEvent(GraphEvent.FirstParentToggled)
        advanceUntilIdle()
        assertEquals(true, commits.firstParents.last())
        assertEquals(
            GraphViewState(branches = GraphBranches.CURRENT, hideRemotes = true, firstParent = true),
            ui.value.graph,
        )

        val loads = commits.scopes.size
        vm.onEvent(GraphEvent.AuthorKindToggled(GraphAuthorKind.BOT))
        advanceUntilIdle()
        assertEquals(loads, commits.scopes.size, "dimming doesn't reload")
        assertEquals(setOf(GraphAuthorKind.PERSON, GraphAuthorKind.AI_ASSISTED), ui.value.graph.authorKinds)
        assertTrue(vm.state.value.dimming)
    }

    @Test
    fun `hiding remotes hides every remote branch by name`() = runTest(dispatcher) {
        val vm = opened()
        vm.onEvent(GraphEvent.HideRemotesToggled)
        advanceUntilIdle()
        assertEquals(LogScope.All(setOf("refs/remotes/origin/main")), commits.scopes.last())
    }

    @Test
    fun `filtering to nothing offers every branch again, clearing the solo`() = runTest(dispatcher) {
        ui.value = RepositoryUiState(soloRefs = setOf("refs/heads/feature/login"))
        commits.rows = emptyList()
        val vm = opened()
        assertTrue(vm.state.value.filteredToNothing)
        assertEquals(1, vm.state.value.soloCount)
        commits.rows = Sample.rows
        vm.onEvent(GraphEvent.ShowAllBranches)
        advanceUntilIdle()
        assertEquals(emptySet(), ui.value.soloRefs)
        assertEquals(LogScope.All(), commits.scopes.last())
        assertEquals(5, vm.state.value.rowCount)
    }

    @Test
    fun `the keyboard moves through rows, parents, children and refs`() = runTest(dispatcher) {
        val vm = opened()
        vm.onEvent(GraphEvent.MovedBy(1))
        advanceUntilIdle()
        assertEquals(Selection.Wip, vm.state.value.selection, "the WIP row comes first")
        vm.onEvent(GraphEvent.MovedBy(1))
        advanceUntilIdle()
        assertEquals(0, vm.selectedRow())

        vm.onEvent(GraphEvent.OtherParent)
        advanceUntilIdle()
        assertEquals(2, vm.selectedRow(), "the merge's second parent")
        vm.onEvent(GraphEvent.FirstParent)
        advanceUntilIdle()
        assertEquals(3, vm.selectedRow())
        vm.onEvent(GraphEvent.FirstChild)
        advanceUntilIdle()
        assertEquals(2, vm.selectedRow(), "the nearest child above")
        vm.onEvent(GraphEvent.NextRef(+1))
        advanceUntilIdle()
        assertEquals(3, vm.selectedRow(), "v1.0")
        vm.onEvent(GraphEvent.MovedBy(Int.MAX_VALUE / 2))
        advanceUntilIdle()
        assertEquals(4, vm.selectedRow())
        vm.onEvent(GraphEvent.JumpToHead)
        advanceUntilIdle()
        assertEquals(0, vm.selectedRow())
    }

    @Test
    fun `Enter opens the selected commit and Mod+C copies its full hash`() = runTest(dispatcher) {
        val vm = opened()
        val effects = mutableListOf<GraphEffect>()
        val collector = launch { vm.effects.collect { effects += it } }
        vm.onEvent(GraphEvent.Selected(Selection.Row(1)))
        vm.onEvent(GraphEvent.OpenSelected)
        vm.onEvent(GraphEvent.CopyHash)
        advanceUntilIdle()
        assertEquals(
            listOf(GraphEffect.OpenCommit(Sample.id(4)), GraphEffect.CopyToClipboard(Sample.id(4).hex)),
            effects,
        )
        collector.cancel()
    }

    @Test
    fun `a hash prefix matches at once, a message through git, and Enter steps through matches`() =
        runTest(dispatcher) {
            val vm = opened()
            vm.onEvent(GraphEvent.SearchTyped(Sample.id(3).short()))
            advanceUntilIdle()
            assertEquals(listOf(2), vm.state.value.search.matchRows)

            vm.onEvent(GraphEvent.SearchTyped("login"))
            advanceUntilIdle()
            val search = vm.state.value.search
            assertEquals(listOf(0, 1), search.matchRows)
            assertEquals(false, search.running)
            vm.onEvent(GraphEvent.NextMatch)
            advanceUntilIdle()
            assertEquals(0, vm.selectedRow())
            assertEquals(0, vm.state.value.search.current)
            vm.onEvent(GraphEvent.NextMatch)
            vm.onEvent(GraphEvent.NextMatch)
            advanceUntilIdle()
            assertEquals(0, vm.selectedRow(), "it wraps around")
            vm.onEvent(GraphEvent.PreviousMatch)
            advanceUntilIdle()
            assertEquals(1, vm.selectedRow())
            vm.onEvent(GraphEvent.SearchCleared)
            advanceUntilIdle()
            assertTrue(!vm.state.value.search.active && !vm.state.value.dimming)
        }

    @Test
    fun `a failing log keeps its rows and the error, and Retry loads again`() = runTest(dispatcher) {
        commits.failure = IllegalStateException("git died")
        val vm = opened()
        val state = vm.state.value
        assertEquals(Phase.Failed, state.phase)
        assertEquals(2, state.rowCount)
        assertIs<IllegalStateException>(state.error)
        commits.failure = null
        vm.onEvent(GraphEvent.Retry)
        advanceUntilIdle()
        assertEquals(Phase.Loaded, vm.state.value.phase)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `moving refs reload the graph and keep the selected commit selected`() = runTest(dispatcher) {
        val vm = opened()
        vm.onEvent(GraphEvent.Selected(Selection.Row(2)))
        advanceUntilIdle()
        // A new commit on main: the history gains a row at the top.
        val newTip = com.srctool.graft.data.git.api.models.CommitRow(
            Sample.id(6),
            listOf(Sample.id(5)),
            0,
            Sample.now,
            0,
            Sample.now,
            "New work",
        )
        commits.rows = listOf(newTip) + Sample.rows
        session.state.value =
            Sample.snapshot(
                refs = listOf(Ref("refs/heads/main", Sample.id(6), isHead = true)) + Sample.refs.drop(1),
                head = Sample.id(6),
            )
        advanceUntilIdle()
        assertEquals(2, commits.scopes.size)
        assertEquals(6, vm.state.value.rowCount)
        assertEquals(3, vm.selectedRow(), "Add retry moved down a row")
    }

    @Test
    fun `a slow load without a commit-graph file offers one once, and writing it reloads`() = runTest(dispatcher) {
        commits.hasGraph = false
        tick = GraphViewModel.SLOW_NANOS
        val vm = opened()
        assertEquals(CommitGraphOffer.Shown, vm.state.value.commitGraph)
        vm.onEvent(GraphEvent.SpeedUpAccepted)
        advanceUntilIdle()
        assertEquals(1, commits.graphWrites)
        assertEquals(1, session.writes, "through the write queue")
        assertEquals(CommitGraphOffer.Hidden, vm.state.value.commitGraph)
        assertTrue(ui.value.graph.commitGraphOffered)
        assertEquals(2, commits.scopes.size, "reloaded")
    }

    @Test
    fun `declining the offer is remembered and it isn't made again`() = runTest(dispatcher) {
        commits.hasGraph = false
        tick = GraphViewModel.SLOW_NANOS
        val vm = opened()
        vm.onEvent(GraphEvent.SpeedUpDeclined)
        advanceUntilIdle()
        assertTrue(ui.value.graph.commitGraphOffered)
        assertEquals(0, commits.graphWrites)
        vm.onEvent(GraphEvent.Retry)
        advanceUntilIdle()
        assertEquals(CommitGraphOffer.Hidden, vm.state.value.commitGraph)
    }

    @Test
    fun `column widths are saved as the table reports them`() = runTest(dispatcher) {
        val vm = opened()
        vm.onEvent(GraphEvent.ColumnsChanged(mapOf("author" to 200f, "sha" to null)))
        advanceUntilIdle()
        assertEquals(mapOf("author" to 200f, "sha" to null), ui.value.columns)
        assertEquals(ui.value.columns, vm.state.value.columns)
    }

    @Suppress("unused")
    private suspend fun drain(vm: GraphViewModel) = vm.effects.toList()

    @Test
    fun `hiding or soloing in the left panel reloads the graph over what's shown`() = runTest(dispatcher) {
        opened()
        shared.update { it.copy(soloRefs = setOf("refs/heads/feature/login")) }
        advanceUntilIdle()
        assertEquals(LogScope.Only(setOf("refs/heads/feature/login")), commits.scopes.last())
        val loads = commits.scopes.size
        shared.update { it.copy(pinnedRefs = setOf("refs/heads/main")) }
        advanceUntilIdle()
        assertEquals(loads, commits.scopes.size, "pinning only reorders the panel")
    }

    @Test
    fun `the selection is shared with the other panes, both ways`() = runTest(dispatcher) {
        val vm = opened()
        vm.onEvent(GraphEvent.Selected(Selection.Row(1)))
        advanceUntilIdle()
        assertEquals(ViewSelection.Commit(Sample.id(4)), shared.selection.value)
        vm.onEvent(GraphEvent.Selected(Selection.Wip))
        advanceUntilIdle()
        assertEquals(ViewSelection.Wip, shared.selection.value)

        // The left panel picks v1.0's commit.
        shared.selection.value = ViewSelection.Commit(Sample.id(2))
        advanceUntilIdle()
        assertEquals(3, vm.selectedRow())
    }

    @Test
    fun `a stash sits just above its base commit, and the keys move through it`() = runTest(dispatcher) {
        stashes.entries = listOf(sampleStash)
        val vm = opened()
        assertEquals(listOf(StashPlacement(sampleStash, 1)), vm.state.value.stashes)
        vm.onEvent(GraphEvent.Selected(Selection.Row(0)))
        vm.onEvent(GraphEvent.MovedBy(1))
        advanceUntilIdle()
        assertEquals(Selection.Stash(sampleStash.id), vm.state.value.selection)
        assertEquals(ViewSelection.Commit(sampleStash.id), shared.selection.value, "the details show the stash")
        vm.onEvent(GraphEvent.MovedBy(1))
        advanceUntilIdle()
        assertEquals(1, vm.selectedRow())
    }
}
