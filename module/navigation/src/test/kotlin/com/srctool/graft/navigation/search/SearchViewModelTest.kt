// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.search

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.srctool.graft.presentation.search.SearchCandidate
import com.srctool.graft.presentation.search.SearchGroup
import com.srctool.graft.presentation.search.SearchKind
import com.srctool.graft.presentation.search.SearchScope
import com.srctool.graft.presentation.search.SearchSource
import com.srctool.graft.presentation.search.SearchTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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

private class FakeSource(
    override val group: SearchGroup,
    override val needsRepository: Boolean = false,
    override val slow: Boolean = false,
    var items: List<SearchCandidate> = emptyList(),
    var recents: List<SearchCandidate> = emptyList(),
    var failure: Exception? = null,
) : SearchSource {
    val queries = ArrayList<String>()

    override suspend fun candidates(query: String, scope: SearchScope): List<SearchCandidate> {
        queries += query
        failure?.let { throw it }
        return if (query.isBlank()) recents else items
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    private val repos =
        FakeSource(
            SearchGroup.Repositories,
            items = listOf(
                candidate("graft", SearchKind.Repository),
                candidate("login-service", SearchKind.Repository),
            ),
            recents = listOf(candidate("graft", SearchKind.Repository)),
        )
    private val refs =
        FakeSource(
            SearchGroup.Refs,
            needsRepository = true,
            items = listOf(candidate("feature/login"), candidate("main"), candidate("v1-login", SearchKind.Tag)),
        )
    private val commits =
        FakeSource(
            SearchGroup.Commits,
            needsRepository = true,
            slow = true,
            items = listOf(candidate("Add login form", SearchKind.Commit)),
        )
    private val actions =
        FakeSource(
            SearchGroup.Actions,
            items = listOf(candidate("Settings…", SearchKind.Action), candidate("About Graft", SearchKind.Action)),
            recents = listOf(candidate("Settings…", SearchKind.Action)),
        )

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    /** [inRepository] says whether the scope has a repository; the fakes never touch it. */
    private fun TestScope.opened(inRepository: Boolean = true): SearchViewModel {
        val scope =
            if (inRepository) {
                SearchScope(RepositoryScopeStub.instance)
            } else {
                SearchScope()
            }
        val vm =
            ViewModelProvider.create(
                store,
                viewModelFactory {
                    initializer {
                        SearchViewModel(listOf(repos, refs, commits, actions), scope, dispatcher, pause = 150)
                    }
                },
            )[SearchViewModel::class]
        vm.onEvent(SearchEvent.Opened)
        advanceUntilIdle()
        return vm
    }

    private fun TestScope.send(vm: SearchViewModel, vararg events: SearchEvent) {
        events.forEach(vm::onEvent)
        advanceUntilIdle()
    }

    private fun SearchViewModel.titles() = state.value.shown.map { it.ranked.candidate.title }

    @Test
    fun `before anything is typed, recent repositories and actions show, in the sources' order`() =
        runTest(dispatcher) {
            val vm = opened()
            assertEquals(listOf("graft", "Settings…"), vm.titles())
        }

    @Test
    fun `typing ranks each group, and the groups that ask git fill in after the pause`() = runTest(dispatcher) {
        val vm = opened()
        vm.onEvent(SearchEvent.QueryTyped("login"))
        advanceTimeBy(100)
        assertEquals(listOf("login-service", "v1-login", "feature/login"), vm.titles())
        assertIs<GroupState.Loading>(vm.state.value.groups.getValue(SearchGroup.Commits))
        assertTrue(vm.state.value.searching)
        advanceUntilIdle()
        assertEquals(
            listOf("login-service", "v1-login", "feature/login", "Add login form"),
            vm.titles(),
        )
        assertEquals(false, vm.state.value.searching)
    }

    @Test
    fun `typing again before git is asked cancels the earlier search`() = runTest(dispatcher) {
        val vm = opened()
        commits.queries.clear()
        vm.onEvent(SearchEvent.QueryTyped("lo"))
        advanceTimeBy(100)
        vm.onEvent(SearchEvent.QueryTyped("log"))
        advanceUntilIdle()
        assertEquals(listOf("log"), commits.queries)
    }

    @Test
    fun `a group that fails shows its own error, keeps the others, and can be retried`() = runTest(dispatcher) {
        val vm = opened()
        commits.failure = IllegalStateException("git said no")
        send(vm, SearchEvent.QueryTyped("login"))
        assertIs<GroupState.Failed>(vm.state.value.groups.getValue(SearchGroup.Commits))
        assertEquals(listOf("login-service", "v1-login", "feature/login"), vm.titles())
        commits.failure = null
        send(vm, SearchEvent.Retried(SearchGroup.Commits))
        assertIs<GroupState.Ready>(vm.state.value.groups.getValue(SearchGroup.Commits))
        assertTrue("Add login form" in vm.titles())
    }

    @Test
    fun `on Home the groups that need a repository aren't asked, and the state says so`() = runTest(dispatcher) {
        val vm = opened(inRepository = false)
        refs.queries.clear()
        send(vm, SearchEvent.QueryTyped("login"))
        assertEquals(emptyList(), refs.queries)
        assertTrue(vm.state.value.needsRepository)
        assertEquals(listOf("login-service"), vm.titles())
    }

    @Test
    fun `All shows the best five of each group, and a tab shows its own`() = runTest(dispatcher) {
        repos.items = (1..8).map { candidate("repo-$it", SearchKind.Repository) }
        val vm = opened()
        send(vm, SearchEvent.QueryTyped("repo"))
        assertEquals(5, vm.state.value.shown.size)
        assertEquals(8, vm.state.value.total)
        send(vm, SearchEvent.TabChosen(SearchTab.Repositories))
        assertEquals(8, vm.state.value.shown.size)
        assertEquals(8, vm.state.value.count(SearchTab.Repositories))
        assertEquals(8, vm.state.value.count(SearchTab.All))
    }

    @Test
    fun `tabs move around, and the selection stays on a result`() = runTest(dispatcher) {
        val vm = opened()
        send(vm, SearchEvent.TabMoved(-1))
        assertEquals(SearchTab.Actions, vm.state.value.tab)
        send(vm, SearchEvent.TabMoved(1))
        assertEquals(SearchTab.All, vm.state.value.tab)
        send(vm, SearchEvent.Moved(10))
        assertEquals(1, vm.state.value.selected)
        send(vm, SearchEvent.Moved(-10))
        assertEquals(0, vm.state.value.selected)
    }

    @Test
    fun `Enter sends the selected result's target, and nothing when there are no results`() = runTest(dispatcher) {
        val vm = opened()
        val effects = mutableListOf<SearchEffect>()
        val collector = launch { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        send(vm, SearchEvent.Moved(1), SearchEvent.Activated)
        assertEquals(listOf<SearchEffect>(SearchEffect.Activate(SearchTarget.RunAction("Settings…"))), effects)
        send(vm, SearchEvent.QueryTyped("zzzzzz"), SearchEvent.Activated)
        assertEquals(1, effects.size)
        collector.cancel()
    }

    @Test
    fun `Esc clears the text first, and closes on the second press`() = runTest(dispatcher) {
        val vm = opened()
        val effects = mutableListOf<SearchEffect>()
        val collector = launch { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        send(vm, SearchEvent.QueryTyped("login"), SearchEvent.Escaped)
        assertEquals("", vm.state.value.query)
        assertEquals(emptyList(), effects)
        send(vm, SearchEvent.Escaped)
        assertEquals(listOf<SearchEffect>(SearchEffect.Close), effects)
        collector.cancel()
    }
}
