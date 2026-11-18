// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.graph

import androidx.lifecycle.viewModelScope
import com.srctool.graft.data.app.api.models.GraphAuthorKind
import com.srctool.graft.data.app.api.models.GraphBranches
import com.srctool.graft.data.app.api.models.RepositoryUiState
import com.srctool.graft.data.git.api.models.AuthorType
import com.srctool.graft.data.git.api.models.Head
import com.srctool.graft.data.git.api.models.LogQuery
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.RefKind
import com.srctool.graft.data.git.api.models.StashEntry
import com.srctool.graft.data.git.api.repositories.CommitRepository
import com.srctool.graft.data.git.api.repositories.StashRepository
import com.srctool.graft.feature.graph.domain.api.models.GraphFilter
import com.srctool.graft.feature.graph.domain.api.models.GraphLoad
import com.srctool.graft.feature.graph.domain.api.models.GraphSearch
import com.srctool.graft.feature.graph.domain.api.models.LaidOutGraph
import com.srctool.graft.feature.graph.domain.api.usecases.GraphMatch
import com.srctool.graft.feature.graph.domain.api.usecases.LoadGraphUseCase
import com.srctool.graft.feature.graph.domain.api.usecases.SearchGraphUseCase
import com.srctool.graft.feature.graph.presentation.graph.GraphState.Phase
import com.srctool.graft.presentation.session.RepoSnapshot
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.session.SnapshotPart
import com.srctool.graft.presentation.view.CenterView
import com.srctool.graft.presentation.view.RepositoryViewState
import com.srctool.graft.presentation.view.ViewSelection
import com.srctool.sinew.models.Result
import com.srctool.sinew.viewmodel.EventActionViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * The graph screen (S4's center): loads the history through [loadGraph] over the bar's filters, follows the
 * repository [session] for refs, HEAD and the WIP row (reloading when refs move), searches through [searchGraph], and
 * makes D8's commit-graph offer. The bar's choices and column widths are saved per repository (ADR-27).
 *
 * [clock] gives epoch milliseconds, [nanos] a monotonic clock for the offer's timing; tests pass their own.
 */
internal class GraphViewModel(
    private val repository: Path,
    private val session: RepositorySession,
    private val loadGraph: LoadGraphUseCase,
    private val searchGraph: SearchGraphUseCase,
    private val commits: CommitRepository,
    private val stashes: StashRepository,
    private val shared: RepositoryViewState,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nanos: () -> Long = System::nanoTime,
) : EventActionViewModel<GraphState, GraphEvent, GraphAction, GraphEffect>(GraphState()) {
    private var opened = false
    private val saved: RepositoryUiState get() = shared.ui.value
    private var loadJob: Job? = null
    private var searchJob: Job? = null

    /** What the current graph was loaded for: refs and HEAD; when either moves, the graph is stale. */
    private var loadedFor: Any? = null
    private var otherParentStep = 0

    /** The merge Alt+→ last cycled from; Alt+→ on one of its parents keeps cycling. */
    private var cycleFrom = -1

    override fun mapEventToAction(event: GraphEvent): GraphAction? {
        val state = currentState
        return when (event) {
            GraphEvent.Opened -> if (opened) null else GraphAction.Start.also { opened = true }

            GraphEvent.Retry -> GraphAction.Reload

            is GraphEvent.BranchesChosen ->
                GraphAction.ChangeView(reload = true) { it.copy(branches = event.branches) }

            GraphEvent.ShowAllBranches -> GraphAction.ClearSolo

            GraphEvent.HideRemotesToggled ->
                GraphAction.ChangeView(reload = true) { it.copy(hideRemotes = !it.hideRemotes) }

            GraphEvent.FirstParentToggled ->
                GraphAction.ChangeView(reload = true) { it.copy(firstParent = !it.firstParent) }

            is GraphEvent.AuthorKindToggled ->
                GraphAction.ChangeView(reload = false) { view ->
                    val kinds = view.authorKinds
                    val next = if (event.kind in kinds) kinds - event.kind else kinds + event.kind
                    // Leaving every kind out would dim everything; it means "no author filter" instead.
                    view.copy(authorKinds = next.ifEmpty { GraphAuthorKind.entries.toSet() })
                }

            is GraphEvent.SearchTyped -> GraphAction.Search(event.query, state.search.kind)

            is GraphEvent.SearchKindChosen -> GraphAction.Search(state.search.query, event.kind)

            GraphEvent.SearchCleared -> GraphAction.Search("", state.search.kind)

            GraphEvent.NextMatch -> GraphAction.SelectBy { matchStep(it, +1) }

            GraphEvent.PreviousMatch -> GraphAction.SelectBy { matchStep(it, -1) }

            is GraphEvent.Selected -> GraphAction.Select(event.selection)

            is GraphEvent.MovedBy -> GraphAction.SelectBy { movedBy(it, event.rows) }

            GraphEvent.FirstParent -> GraphAction.SelectBy { fromRow(it) { moves, row -> moves.firstParent(row) } }

            GraphEvent.FirstChild -> GraphAction.SelectBy { fromRow(it) { moves, row -> moves.firstChild(row) } }

            GraphEvent.OtherParent -> if (state.selection is Selection.Row) GraphAction.OtherParent else null

            is GraphEvent.NextRef ->
                GraphAction.SelectBy { now ->
                    fromRow(now) { moves, row ->
                        val graph = now.graph!!
                        moves.nextRef(row, event.direction) { now.refs.containsKey(graph[it].commit.id) }
                    }
                }

            GraphEvent.JumpToHead ->
                GraphAction.SelectBy { now -> now.head?.let { moves(now)?.rowOf(it) }?.let(Selection::Row) }

            GraphEvent.OpenSelected -> GraphAction.Open

            is GraphEvent.Marked -> GraphAction.Mark(event.selection)

            GraphEvent.MarkSelected -> GraphAction.Mark(null)

            GraphEvent.CompareMarked -> GraphAction.CompareMarked

            GraphEvent.CopyHash -> GraphAction.CopyHash

            is GraphEvent.OutputShown -> GraphAction.ShowOutput(event.shown)

            GraphEvent.SpeedUpAccepted ->
                if (state.commitGraph == CommitGraphOffer.Shown) GraphAction.WriteCommitGraph else null

            GraphEvent.SpeedUpDeclined ->
                if (state.commitGraph == CommitGraphOffer.Shown) GraphAction.DeclineCommitGraph else null

            is GraphEvent.ColumnsChanged -> if (event.columns ==
                state.columns
            ) {
                null
            } else {
                GraphAction.SaveColumns(event.columns)
            }
        }
    }

    override suspend fun onAction(action: GraphAction) {
        when (action) {
            GraphAction.Start -> start()

            GraphAction.Reload -> reload()

            is GraphAction.ChangeView -> {
                val view = action.change(currentState.view)
                setState { copy(view = view) }
                save { it.copy(graph = view) }
                if (action.reload) reload()
            }

            GraphAction.ClearSolo -> {
                val view = currentState.view.copy(branches = GraphBranches.ALL)
                setState { copy(view = view, soloCount = 0) }
                save { it.copy(graph = view, soloRefs = emptySet()) }
                reload()
            }

            is GraphAction.Search -> search(action.query, action.kind)

            is GraphAction.Select -> select(action.selection)

            is GraphAction.SelectBy -> action.pick(currentState)?.let(::select)

            GraphAction.OtherParent -> {
                val state = currentState
                val selected = (state.selection as? Selection.Row)?.index ?: return
                val row = if (cycleFrom >= 0) cycleFrom else selected
                val target = moves(state)?.otherParent(row, otherParentStep) ?: return
                otherParentStep++
                // Alt+→ again cycles from the merge, so the step is kept while the selection is one of its parents.
                setState {
                    copy(
                        selection = Selection.Row(target),
                        search = search.copy(current = search.matchRows.indexOf(target)),
                    )
                }
                publish(Selection.Row(target))
                cycleFrom = row
            }

            GraphAction.CopyHash ->
                commitAt(currentState, currentState.selection)?.let { emitEffect(GraphEffect.CopyToClipboard(it.hex)) }

            GraphAction.Open -> commitAt(currentState, currentState.selection)?.let {
                emitEffect(GraphEffect.OpenCommit(it))
            }

            is GraphAction.Mark -> {
                val id = commitAt(currentState, action.selection ?: currentState.selection) ?: return
                shared.marked.value = if (shared.marked.value == id) null else id
            }

            GraphAction.CompareMarked -> {
                // A is the selected commit (picked first), B the marked one (Task 15 wireframe, question 1).
                val a = commitAt(currentState, currentState.selection) ?: return
                val b = shared.marked.value ?: return
                if (a != b) shared.center.value = CenterView.Compare(a.hex, b.hex)
            }

            GraphAction.WriteCommitGraph -> writeCommitGraph()

            GraphAction.DeclineCommitGraph -> {
                setState { copy(commitGraph = CommitGraphOffer.Hidden) }
                markOffered()
            }

            is GraphAction.SaveColumns -> {
                setState { copy(columns = action.columns) }
                save { it.copy(columns = action.columns) }
            }

            is GraphAction.ShowOutput -> setState { copy(outputShown = action.shown) }
        }
    }

    private suspend fun start() {
        // A missing or unreadable state.json means the defaults; the screen still opens.
        val loaded = shared.load()
        setState { copy(view = loaded.graph, columns = loaded.columns, soloCount = loaded.soloRefs.size) }
        viewModelScope.launch {
            session.snapshot.collect { snapshot -> onSnapshot(snapshot) }
        }
        // The left panel hides and solos refs through the shared state: the graph walks what's shown.
        viewModelScope.launch {
            var walked = loaded.hiddenRefs to loaded.soloRefs
            shared.ui.collect { ui ->
                val now = ui.hiddenRefs to ui.soloRefs
                if (now != walked) {
                    walked = now
                    if (loadedFor != null) reload()
                }
            }
        }
        viewModelScope.launch { shared.marked.collect { id -> setState { copy(marked = id) } } }
        // Another pane (the left panel, a parent link in the details) selects a commit: show it here.
        viewModelScope.launch {
            shared.selection.collect { selected -> follow(selected) }
        }
    }

    /** A commit another pane selected that isn't laid out yet. */
    private var pending: ObjectId? = null

    private fun follow(selected: ViewSelection) {
        val state = currentState
        when (selected) {
            ViewSelection.None -> if (state.selection != Selection.None) setState { copy(selection = Selection.None) }

            ViewSelection.Wip -> if (state.selection != Selection.Wip) setState { copy(selection = Selection.Wip) }

            is ViewSelection.Commit -> {
                pending = null
                if (commitAt(state, state.selection) == selected.id) return
                if (state.stashes.any { it.entry.id == selected.id }) {
                    setState { copy(selection = Selection.Stash(selected.id)) }
                    return
                }
                // Not laid out yet (or filtered out): looked for again as rows arrive.
                val row = moves(state)?.rowOf(selected.id) ?: run {
                    pending = selected.id
                    return
                }
                setState {
                    copy(selection = Selection.Row(row), search = search.copy(current = search.matchRows.indexOf(row)))
                }
            }
        }
    }

    private fun onSnapshot(snapshot: RepoSnapshot) {
        val head = snapshot.head
        val headCommit =
            when (head) {
                is Head.Branch -> head.commit
                is Head.Detached -> head.commit
                null -> null
            }
        setState {
            copy(
                refs = refLabels(snapshot.refs),
                head = headCommit,
                wipFiles = snapshot.summary?.takeIf {
                    it.dirty
                }?.changedFiles,
            )
        }
        // The first load waits for the refs, so stale hide and solo choices can be pruned against them.
        if (SnapshotPart.REFS !in snapshot.loaded && SnapshotPart.REFS !in snapshot.failures) return
        val key = snapshot.refs.map { it.name to it.commit } to headCommit
        if (key == loadedFor) return
        loadedFor = key
        latestRefs = snapshot.refNames
        unborn = head is Head.Branch && head.commit == null && snapshot.refs.none { it.kind != RefKind.OTHER }
        reload()
    }

    private var latestRefs: Set<String> = emptySet()
    private var unborn = false

    private fun reload() {
        loadJob?.cancel()
        if (unborn) {
            setState { copy(phase = Phase.Empty, graph = null, rowCount = 0, error = null) }
            return
        }
        val view = currentState.view
        val remotes = if (view.hideRemotes) {
            latestRefs.filterTo(HashSet()) {
                it.startsWith(RefKind.REMOTE_BRANCH.prefix)
            }
        } else {
            emptySet()
        }
        val filter =
            GraphFilter(
                hiddenRefs = saved.hiddenRefs + remotes,
                soloRefs = saved.soloRefs,
                currentBranch = view.branches == GraphBranches.CURRENT,
                firstParent = view.firstParent,
            ).prunedTo(latestRefs)
        val selectedId = commitAt(currentState, currentState.selection)
        setState {
            copy(
                phase = Phase.Loading,
                error = null,
                now = clock() / 1000,
                soloCount = filter.soloRefs.size,
                stashes = emptyList(),
            )
        }
        stashList = emptyList()
        stashScan = 0
        stashRows.clear()
        viewModelScope.launch {
            // Without its stashes the graph still shows; a failed read leaves them out.
            (stashes.list(repository) as? Result.Success)?.data?.let {
                stashList = it
                placeStashes()
            }
        }
        val started = nanos()
        loadJob =
            viewModelScope.launch {
                var reselected = selectedId == null
                loadGraph.load(repository, filter).collect { step ->
                    setState {
                        when (step) {
                            is GraphLoad.Progress -> copy(
                                graph = step.graph,
                                rowCount = step.rowCount,
                                widest = step.widest,
                            )

                            is GraphLoad.Loaded ->
                                copy(
                                    graph = step.graph,
                                    rowCount = step.rowCount,
                                    widest = step.widest,
                                    phase = if (step.rowCount == 0 && isDefault(filter)) Phase.Empty else Phase.Loaded,
                                )

                            is GraphLoad.Failed -> copy(graph = step.graph, phase = Phase.Failed, error = step.error)
                        }
                    }
                    if (!reselected) {
                        // Keep the selected commit selected across a reload, once its row is laid out.
                        GraphMoves(step.graph.size) { step.graph[it].commit }.rowOf(selectedId!!)?.let {
                            setState { copy(selection = Selection.Row(it)) }
                            reselected = true
                        }
                    }
                    // A commit another pane selected before its row was laid out.
                    pending?.let { wanted ->
                        if (shared.selection.value == ViewSelection.Commit(wanted)) follow(ViewSelection.Commit(wanted))
                    }
                    placeStashes()
                    refreshMatches()
                    if (step !is GraphLoad.Failed) maybeOffer(started, step.graph.size)
                }
            }
    }

    private var stashList: List<StashEntry> = emptyList()
    private var stashScan = 0
    private val stashRows = HashMap<ObjectId, Int>()

    /**
     * Puts each stash above its base commit's row once that row is laid out. Only rows not yet looked at are scanned,
     * so a long load scans each row once.
     */
    private fun placeStashes() {
        val state = currentState
        val graph = state.graph ?: return
        if (stashList.isEmpty()) return
        val wanted = stashList.mapNotNullTo(HashSet()) { it.base }
        while (stashScan < state.rowCount) {
            val id = graph[stashScan].commit.id
            if (id in wanted) stashRows.putIfAbsent(id, stashScan)
            stashScan++
        }
        val placed =
            stashList
                .mapNotNull { entry -> entry.base?.let(stashRows::get)?.let { StashPlacement(entry, it) } }
                .sortedWith(compareBy({ it.baseRow }, { it.entry.index }))
        if (placed != state.stashes) setState { copy(stashes = placed) }
    }

    private fun isDefault(filter: GraphFilter): Boolean =
        filter.soloRefs.isEmpty() && !filter.currentBranch && !filter.firstParent &&
            !currentState.view.hideRemotes && saved.hiddenRefs.isEmpty()

    private var offerChecked = false

    /** D8: offer a commit-graph file once loading has been slow, if git has none and it wasn't offered before. */
    private fun maybeOffer(started: Long, rows: Int) {
        if (offerChecked || currentState.view.commitGraphOffered) return
        if (nanos() - started < SLOW_NANOS && rows < SLOW_ROWS) return
        offerChecked = true
        viewModelScope.launch {
            val has = commits.hasCommitGraph(repository)
            if (has is Result.Success && !has.data) setState { copy(commitGraph = CommitGraphOffer.Shown) }
        }
    }

    private suspend fun writeCommitGraph() {
        setState { copy(commitGraph = CommitGraphOffer.Writing) }
        val result = session.write { commits.writeCommitGraph(repository) }
        markOffered()
        setState { copy(commitGraph = CommitGraphOffer.Hidden) }
        if (result is Result.Failure) {
            setState { copy(error = result.error, phase = if (rowCount == 0) Phase.Failed else phase) }
        } else {
            reload()
        }
    }

    private suspend fun markOffered() {
        val view = currentState.view.copy(commitGraphOffered = true)
        setState { copy(view = view) }
        save { it.copy(graph = view) }
    }

    private suspend fun save(change: (RepositoryUiState) -> RepositoryUiState) {
        shared.update(change)
    }

    private fun search(query: String, kind: SearchKind) {
        searchJob?.cancel()
        setState { copy(search = SearchState(query = query, kind = kind, running = query.isNotBlank())) }
        if (query.isBlank()) return
        searchJob =
            viewModelScope.launch {
                val graph = currentState.graph
                if (GraphMatch.isHashPrefix(query) && graph != null) {
                    val rows = GraphMatch.hashPrefix(graph, query).filter { it < currentState.rowCount }
                    val ids = rows.mapTo(HashSet()) { graph[it].commit.id }
                    setState { copy(search = search.copy(matches = ids, matchRows = rows, running = false)) }
                    if (rows.isNotEmpty()) return@launch
                }
                // Typing settles before git runs.
                delay(SEARCH_DELAY_MS)
                val logQuery =
                    when (kind) {
                        SearchKind.MESSAGE -> LogQuery(message = query)
                        SearchKind.AUTHOR -> LogQuery(author = query)
                        SearchKind.PATH -> LogQuery(path = query)
                        SearchKind.CONTENT -> LogQuery(content = query)
                    }
                val view = currentState.view
                val filter =
                    GraphFilter(
                        hiddenRefs = saved.hiddenRefs,
                        soloRefs = saved.soloRefs,
                        currentBranch = view.branches == GraphBranches.CURRENT,
                    ).prunedTo(latestRefs)
                searchGraph.search(repository, logQuery.copy(firstParent = view.firstParent), filter).collect { step ->
                    when (step) {
                        is GraphSearch.Found -> setState {
                            copy(search = search.copy(matches = search.matches + step.ids))
                        }

                        is GraphSearch.Done -> setState { copy(search = search.copy(running = false)) }

                        is GraphSearch.Failed -> setState {
                            copy(search = search.copy(running = false, error = step.error))
                        }
                    }
                    refreshMatches()
                }
            }
    }

    /** The rows of the matches, recomputed as rows and matches arrive. */
    private fun refreshMatches() {
        val state = currentState
        val graph = state.graph ?: return
        if (state.search.matches.isEmpty()) return
        val rows = (0 until state.rowCount).filter { graph[it].commit.id in state.search.matches }
        if (rows != state.search.matchRows) setState { copy(search = search.copy(matchRows = rows)) }
    }

    private fun matchStep(state: GraphState, direction: Int): Selection? {
        val rows = state.search.matchRows
        if (rows.isEmpty()) return null
        val selected = (state.selection as? Selection.Row)?.index ?: -1
        val next =
            if (direction > 0) {
                rows.firstOrNull { it > selected } ?: rows.first()
            } else {
                rows.lastOrNull { it < selected } ?: rows.last()
            }
        return Selection.Row(next)
    }

    /** Selects, keeping the "3 of 12" index on the selected match, and ends an Alt+→ cycle. */
    private fun select(selection: Selection) {
        otherParentStep = 0
        cycleFrom = -1
        val row = (selection as? Selection.Row)?.index ?: -1
        setState { copy(selection = selection, search = search.copy(current = search.matchRows.indexOf(row))) }
        publish(selection)
    }

    /** Tells the other panes what's selected here. */
    private fun publish(selection: Selection) {
        shared.selection.value =
            when (selection) {
                Selection.None -> ViewSelection.None
                Selection.Wip -> ViewSelection.Wip
                is Selection.Row -> commitAt(currentState, selection)?.let(ViewSelection::Commit) ?: ViewSelection.None
                is Selection.Stash -> ViewSelection.Commit(selection.id)
            }
    }

    private fun movedBy(state: GraphState, delta: Int): Selection {
        val table = state.table
        val total = table.size
        if (total == 0) return Selection.None
        val current = table.indexOf(state.selection) ?: if (delta > 0) -1 else total
        val target = (current.toLong() + delta).coerceIn(0, total - 1L).toInt()
        return table.selectionAt(target)
    }

    private fun moves(state: GraphState): GraphMoves? {
        val graph = state.graph ?: return null
        return GraphMoves(state.rowCount) { graph[it].commit }
    }

    private fun fromRow(state: GraphState, move: (GraphMoves, Int) -> Int?): Selection? {
        val row = (state.selection as? Selection.Row)?.index ?: return null
        val target = moves(state)?.let { move(it, row) } ?: return null
        return Selection.Row(target)
    }

    private fun commitAt(state: GraphState, selection: Selection): ObjectId? {
        if (selection is Selection.Stash) return selection.id
        val row = (selection as? Selection.Row)?.index ?: return null
        val graph: LaidOutGraph = state.graph ?: return null
        return if (row < state.rowCount) graph[row].commit.id else null
    }

    companion object {
        /** D8: loading is "slow" past 5 s or 100k commits (G1 plan, Task 13 wireframe). */
        const val SLOW_NANOS: Long = 5_000_000_000
        const val SLOW_ROWS: Int = 100_000
        const val SEARCH_DELAY_MS: Long = 250
    }
}

/** The author filter's kinds, in the data layer's terms. */
internal fun GraphAuthorKind.type(): AuthorType = when (this) {
    GraphAuthorKind.PERSON -> AuthorType.PERSON
    GraphAuthorKind.AI_ASSISTED -> AuthorType.AI_ASSISTED
    GraphAuthorKind.BOT -> AuthorType.BOT
}
