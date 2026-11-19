// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.search

import androidx.lifecycle.viewModelScope
import com.srctool.graft.presentation.search.SearchCandidate
import com.srctool.graft.presentation.search.SearchGroup
import com.srctool.graft.presentation.search.SearchScope
import com.srctool.graft.presentation.search.SearchSource
import com.srctool.graft.presentation.search.SearchTarget
import com.srctool.sinew.viewmodel.EventActionViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The tabs: **All**, then one per group. */
internal enum class SearchTab(val group: SearchGroup?) {
    All(null),
    Repositories(SearchGroup.Repositories),
    Refs(SearchGroup.Refs),
    Commits(SearchGroup.Commits),
    Files(SearchGroup.Files),
    Actions(SearchGroup.Actions),
}

/** Where one group's search stands. */
internal sealed interface GroupState {
    data object Loading : GroupState

    data class Ready(val items: List<Ranked>, val note: String? = null) : GroupState

    /** This group failed; the others stay. */
    data class Failed(val error: Throwable) : GroupState

    /** The group needs an open repository and there isn't one. */
    data object NeedsRepository : GroupState
}

/** One row of what's on screen. */
internal data class Shown(val group: SearchGroup, val ranked: Ranked)

internal data class SearchState(
    val query: String = "",
    val tab: SearchTab = SearchTab.All,
    val groups: Map<SearchGroup, GroupState> = emptyMap(),
    val selected: Int = 0,
) {
    private fun ready(group: SearchGroup): List<Ranked> = (groups[group] as? GroupState.Ready)?.items.orEmpty()

    /** What the list shows, in display order: the best [ALL_LIMIT] of each group on **All**, up to [TAB_LIMIT] on a tab. */
    val shown: List<Shown>
        get() = SearchGroup.entries.filter { tab.group == null || it == tab.group }.flatMap { group ->
            ready(group).take(if (tab.group == null) ALL_LIMIT else TAB_LIMIT).map { Shown(group, it) }
        }

    /** How many results the ready groups have in all, for "5 of 12 shown". */
    val total: Int get() = SearchGroup.entries.filter { tab.group == null || it == tab.group }.sumOf { ready(it).size }

    val searching: Boolean get() = groups.values.any { it is GroupState.Loading }

    val needsRepository: Boolean get() = groups.values.any { it is GroupState.NeedsRepository }

    /** Results by tab, for the tab labels. */
    fun count(tab: SearchTab): Int = if (tab.group == null) {
        SearchGroup.entries.sumOf { ready(it).size }
    } else {
        ready(tab.group).size
    }

    val current: Shown? get() = shown.getOrNull(selected)

    companion object {
        const val ALL_LIMIT = 5
        const val TAB_LIMIT = 50
    }
}

internal sealed interface SearchEvent {
    data object Opened : SearchEvent

    data class QueryTyped(val query: String) : SearchEvent

    data class TabChosen(val tab: SearchTab) : SearchEvent

    data class TabMoved(val by: Int) : SearchEvent

    data class Moved(val by: Int) : SearchEvent

    data class Selected(val index: Int) : SearchEvent

    data object Activated : SearchEvent

    /** **Esc**: clears the text first, and closes when there's none. */
    data object Escaped : SearchEvent

    data object Cleared : SearchEvent

    data class Retried(val group: SearchGroup) : SearchEvent
}

internal sealed interface SearchAction {
    data class Search(val query: String) : SearchAction

    class Local(val change: (SearchState) -> SearchState) : SearchAction

    data object Activate : SearchAction

    data object Escape : SearchAction

    data class Retry(val group: SearchGroup) : SearchAction
}

internal sealed interface SearchEffect {
    data class Activate(val target: SearchTarget) : SearchEffect

    data object Close : SearchEffect
}

/**
 * Search Everywhere (S39). Every keystroke searches again: the last search is cancelled, the groups that are quick
 * (repositories, refs, actions) fill in at once, and the ones that ask git (commits, files) after [pause]. A group
 * that fails shows its own error and leaves the rest. [sources] are the features' contributions (D1).
 */
internal class SearchViewModel(
    private val sources: List<SearchSource>,
    private val scope: SearchScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pause: Long = 150,
) : EventActionViewModel<SearchState, SearchEvent, SearchAction, SearchEffect>(SearchState()) {
    private var jobs = HashMap<SearchGroup, Job>()
    private var started = false

    override fun mapEventToAction(event: SearchEvent): SearchAction? = when (event) {
        SearchEvent.Opened -> if (started) null else SearchAction.Search("").also { started = true }

        is SearchEvent.QueryTyped -> SearchAction.Search(event.query)

        is SearchEvent.TabChosen -> SearchAction.Local { it.copy(tab = event.tab, selected = 0) }

        is SearchEvent.TabMoved -> SearchAction.Local { s ->
            val tabs = SearchTab.entries
            s.copy(tab = tabs[(s.tab.ordinal + event.by).mod(tabs.size)], selected = 0)
        }

        is SearchEvent.Moved -> SearchAction.Local { s ->
            s.copy(selected = (s.selected + event.by).coerceIn(0, (s.shown.size - 1).coerceAtLeast(0)))
        }

        is SearchEvent.Selected -> SearchAction.Local { it.copy(selected = event.index) }

        SearchEvent.Activated -> SearchAction.Activate

        SearchEvent.Escaped -> SearchAction.Escape

        SearchEvent.Cleared -> SearchAction.Search("")

        is SearchEvent.Retried -> SearchAction.Retry(event.group)
    }

    override suspend fun onAction(action: SearchAction) {
        when (action) {
            is SearchAction.Search -> search(action.query)

            is SearchAction.Local -> setState(action.change)

            // Checked here, not when the event arrived: moves before it have run by now.
            SearchAction.Activate -> currentState.current?.let {
                emitEffect(SearchEffect.Activate(it.ranked.candidate.target))
            }

            SearchAction.Escape ->
                if (currentState.query.isEmpty()) emitEffect(SearchEffect.Close) else search("")

            is SearchAction.Retry -> sources.filter { it.group == action.group }.forEach { run(it, currentState.query) }
        }
    }

    private fun search(query: String) {
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        setState {
            copy(
                query = query,
                selected = 0,
                groups = sources.associate { it.group to initial(it) },
            )
        }
        sources.filter { !(it.needsRepository && scope.repository == null) }.forEach { run(it, query) }
    }

    private fun initial(source: SearchSource): GroupState =
        if (source.needsRepository && scope.repository == null) GroupState.NeedsRepository else GroupState.Loading

    private fun run(source: SearchSource, query: String) {
        jobs[source.group]?.cancel()
        setState { copy(groups = groups + (source.group to GroupState.Loading)) }
        jobs[source.group] =
            viewModelScope.launch {
                val result =
                    try {
                        // Typing fast cancels this before git is asked.
                        if (source.slow && query.isNotBlank()) delay(pause)
                        val found = withContext(io) { source.candidates(query, scope) }
                        GroupState.Ready(
                            if (query.isBlank()) {
                                found.map {
                                    Ranked(it, 0, emptyList())
                                }
                            } else {
                                SearchRanking.rank(query, found)
                            },
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        GroupState.Failed(e)
                    }
                setState {
                    val next = copy(groups = groups + (source.group to result))
                    next.copy(selected = selected.coerceAtMost(maxOf(next.shown.size - 1, 0)))
                }
            }
    }
}
