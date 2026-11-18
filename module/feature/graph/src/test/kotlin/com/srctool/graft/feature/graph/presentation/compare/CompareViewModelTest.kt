// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.compare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.srctool.graft.data.git.api.models.AheadBehind
import com.srctool.graft.data.git.api.models.CommitLog
import com.srctool.graft.data.git.api.models.DiffTarget
import com.srctool.graft.data.git.api.models.MergePreview
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.Ref
import com.srctool.graft.data.git.api.repositories.CompareRepository
import com.srctool.graft.feature.graph.domain.implementation.usecases.GitCompareSummaryUseCase
import com.srctool.graft.feature.graph.domain.implementation.usecases.GitLoadGraphUseCase
import com.srctool.graft.feature.graph.domain.implementation.usecases.GitSearchGraphUseCase
import com.srctool.graft.feature.graph.domain.implementation.usecases.LaneLayout
import com.srctool.graft.feature.graph.presentation.branches.BranchesEvent
import com.srctool.graft.feature.graph.presentation.branches.BranchesViewModel
import com.srctool.graft.feature.graph.presentation.branches.defaultBranch
import com.srctool.graft.feature.graph.presentation.details.DetailsEvent
import com.srctool.graft.feature.graph.presentation.details.DetailsViewModel
import com.srctool.graft.feature.graph.presentation.graph.FakeCommitRepository
import com.srctool.graft.feature.graph.presentation.graph.FakeSession
import com.srctool.graft.feature.graph.presentation.graph.FakeStashes
import com.srctool.graft.feature.graph.presentation.graph.FakeUiState
import com.srctool.graft.feature.graph.presentation.graph.GraphEvent
import com.srctool.graft.feature.graph.presentation.graph.GraphViewModel
import com.srctool.graft.feature.graph.presentation.graph.Sample
import com.srctool.graft.feature.graph.presentation.graph.Selection
import com.srctool.graft.feature.graph.presentation.screens.FakeDiffs
import com.srctool.graft.presentation.view.CenterView
import com.srctool.graft.presentation.view.RepositoryViewState
import com.srctool.graft.presentation.view.ViewSelection
import com.srctool.sinew.models.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Records what git was asked, and answers from [Sample.rows]: B's side is rows 0–1, A's side is row 2. */
internal class FakeCompare(
    var ahead: Int = 2,
    var behind: Int = 1,
    var base: ObjectId? = Sample.id(2),
    var conflicts: List<String> = emptyList(),
) : CompareRepository {
    val revisions = ArrayList<String>()

    override fun commits(repository: Path, from: String, to: String): CommitLog {
        revisions += listOf(from, to)
        val rows = if (to == Sample.id(5).hex) Sample.rows.take(2) else Sample.rows.drop(2).take(1)
        return CommitLog(rows.asFlow(), Sample.peopleIndex())
    }

    override suspend fun aheadBehind(repository: Path, base: String, compare: String): Result<AheadBehind> {
        revisions += listOf(base, compare)
        return Result.Success(AheadBehind(ahead, behind))
    }

    override suspend fun mergeBase(repository: Path, a: String, b: String): Result<ObjectId?> = Result.Success(base)

    override suspend fun mergePreview(repository: Path, into: String, from: String): Result<MergePreview> =
        Result.Success(MergePreview(conflicts))
}

@OptIn(ExperimentalCoroutinesApi::class)
class CompareViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val session = FakeSession()
    private val shared = RepositoryViewState(Sample.location.commonDir, FakeUiState())
    private val commits = FakeCommitRepository()
    private val compare = FakeCompare()
    private val diffs = FakeDiffs()
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

    private fun TestScope.opened() = make {
        CompareViewModel(repo, session, shared, compare, commits, diffs, GitCompareSummaryUseCase(compare)) {
            Sample.now *
                1000
        }
    }
        .also {
            it.onEvent(CompareEvent.Opened)
            advanceUntilIdle()
        }

    @Test
    fun `compare resolves both sides, then shows the summary, both lists, files and the merge preview`() =
        runTest(dispatcher) {
            val vm = opened()
            shared.center.value = CenterView.Compare("refs/tags/v1.0", "refs/heads/main")
            advanceUntilIdle()
            val s = vm.state.value
            assertEquals(CompareState.Phase.Ready, s.phase)
            assertEquals(2, s.summary?.ahead)
            assertEquals(listOf(Sample.id(5), Sample.id(4)), s.onlyInB.map { it.row.id })
            assertEquals(listOf(Sample.id(3)), s.onlyInA.map { it.row.id })
            assertEquals("Dwiki Riyadi", s.onlyInB.first().author)
            assertTrue(s.preview?.clean == true)
            assertTrue(s.fileRows.isNotEmpty())
            // Only commit ids reached git, never the names as given.
            assertTrue(compare.revisions.all { it.length == 40 }, compare.revisions.toString())
        }

    @Test
    fun `a name git can't resolve, the same commit twice, and a missing B each have their own state`() =
        runTest(dispatcher) {
            val vm = opened()
            shared.center.value = CenterView.Compare("refs/heads/main", "nope")
            advanceUntilIdle()
            assertEquals(CompareState.Phase.NotFound, vm.state.value.phase)
            assertEquals("nope", vm.state.value.missing)
            shared.center.value = CenterView.Compare("refs/heads/main", "refs/remotes/origin/main")
            advanceUntilIdle()
            assertEquals(CompareState.Phase.Same, vm.state.value.phase)
            shared.center.value = CenterView.Compare("refs/heads/main", null)
            advanceUntilIdle()
            assertEquals(CompareState.Phase.Picking, vm.state.value.phase)
        }

    @Test
    fun `no shared history shows the direct difference, and B 0 ahead skips the merge preview`() = runTest(dispatcher) {
        compare.base = null
        compare.ahead = 0
        val vm = opened()
        shared.center.value = CenterView.Compare("refs/tags/v1.0", "refs/heads/main")
        advanceUntilIdle()
        assertEquals(CompareKind.DIRECT, vm.state.value.kind)
        assertEquals(null, vm.state.value.preview)
    }

    @Test
    fun `picking, swapping and a typed revision all go through the center`() = runTest(dispatcher) {
        val vm = opened()
        shared.center.value = CenterView.Compare("refs/heads/main", null)
        advanceUntilIdle()
        vm.onEvent(CompareEvent.PickerOpened(Side.B))
        vm.onEvent(CompareEvent.QueryTyped(Sample.id(3).short()))
        advanceUntilIdle()
        assertEquals(Sample.id(3), vm.state.value.typed)
        vm.onEvent(CompareEvent.Picked(Side.B, Sample.id(3).hex))
        advanceUntilIdle()
        assertEquals(CenterView.Compare("refs/heads/main", Sample.id(3).hex), shared.center.value)
        assertEquals(null, vm.state.value.picker)
        vm.onEvent(CompareEvent.Swapped)
        advanceUntilIdle()
        assertEquals(CenterView.Compare(Sample.id(3).hex, "refs/heads/main"), shared.center.value)
    }

    @Test
    fun `a commit shows in the details, a file opens a diff that leads back here, and Back goes to the graph`() =
        runTest(dispatcher) {
            val vm = opened()
            val center = CenterView.Compare("refs/tags/v1.0", "refs/heads/main")
            shared.center.value = center
            advanceUntilIdle()
            vm.onEvent(CompareEvent.Activated("commit:${Sample.id(4).hex}"))
            advanceUntilIdle()
            assertEquals(ViewSelection.Commit(Sample.id(4)), shared.selection.value)
            vm.onEvent(CompareEvent.Activated("file:src/main/kotlin/login/Login.kt"))
            advanceUntilIdle()
            assertEquals(
                CenterView.Diff(
                    DiffTarget.Since(Sample.id(2).hex, Sample.id(5).hex),
                    "src/main/kotlin/login/Login.kt",
                    center,
                ),
                shared.center.value,
            )
            shared.center.value = center
            vm.onEvent(CompareEvent.Back)
            advanceUntilIdle()
            assertEquals(CenterView.Graph, shared.center.value)
        }

    // Opening Compare from the other panes (Task 15, question 1)

    @Test
    fun `Mod+click marks a second commit and Mod+D compares the selected one with it`() = runTest(dispatcher) {
        val graph =
            make {
                GraphViewModel(
                    repo,
                    session,
                    GitLoadGraphUseCase(commits, ::LaneLayout, dispatcher),
                    GitSearchGraphUseCase(commits, dispatcher),
                    commits,
                    FakeStashes(),
                    shared,
                    clock = { Sample.now * 1000 },
                    nanos = { 0 },
                )
            }
        graph.onEvent(GraphEvent.Opened)
        advanceUntilIdle()
        graph.onEvent(GraphEvent.Selected(Selection.Row(3)))
        graph.onEvent(GraphEvent.Marked(Selection.Row(0)))
        advanceUntilIdle()
        assertEquals(Sample.id(5), shared.marked.value)
        assertEquals(Sample.id(5), graph.state.value.marked)
        graph.onEvent(GraphEvent.CompareMarked)
        advanceUntilIdle()
        assertEquals(CenterView.Compare(Sample.id(2).hex, Sample.id(5).hex), shared.center.value)
        graph.onEvent(GraphEvent.Marked(Selection.Row(0)))
        advanceUntilIdle()
        assertEquals(null, shared.marked.value, "Mod+click again unmarks")
    }

    @Test
    fun `the details offer Compare when a second commit is marked`() = runTest(dispatcher) {
        val details = make { DetailsViewModel(repo, session, shared, commits) { Sample.now * 1000 } }
        details.onEvent(DetailsEvent.Opened)
        shared.selection.value = ViewSelection.Commit(Sample.id(4))
        shared.marked.value = Sample.id(1)
        advanceUntilIdle()
        assertEquals(Sample.id(1), details.state.value.marked)
        details.onEvent(DetailsEvent.CompareMarked)
        advanceUntilIdle()
        assertEquals(CenterView.Compare(Sample.id(4).hex, Sample.id(1).hex), shared.center.value)
    }

    @Test
    fun `a branch compares with the default branch or its upstream`() = runTest(dispatcher) {
        val panel = make { BranchesViewModel(repo, session, shared, FakeStashes()) }
        panel.onEvent(BranchesEvent.Opened)
        advanceUntilIdle()
        assertEquals("refs/heads/main", panel.state.value.defaultBranch)
        panel.onEvent(BranchesEvent.CompareWith("refs/heads/main", "refs/heads/feature/login"))
        advanceUntilIdle()
        assertEquals(CenterView.Compare("refs/heads/main", "refs/heads/feature/login"), shared.center.value)
    }

    @Test
    fun `the default branch is origin's HEAD, else main, else master`() {
        val id = Sample.id(1)
        assertEquals(
            "refs/remotes/origin/trunk",
            defaultBranch(
                listOf(
                    Ref("refs/heads/main", id),
                    Ref("refs/remotes/origin/trunk", id),
                    Ref("refs/remotes/origin/HEAD", id, symbolicTarget = "refs/remotes/origin/trunk"),
                ),
            ),
        )
        assertEquals(
            "refs/heads/master",
            defaultBranch(listOf(Ref("refs/heads/master", id), Ref("refs/heads/dev", id))),
        )
        assertEquals(null, defaultBranch(listOf(Ref("refs/heads/dev", id))))
    }
}
