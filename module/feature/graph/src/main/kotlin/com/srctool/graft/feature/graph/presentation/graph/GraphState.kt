// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.graph

import com.srctool.graft.core.ui.git.RefLabel
import com.srctool.graft.data.app.api.models.GraphViewState
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.feature.graph.domain.api.models.LaidOutGraph

/** What the graph screen shows (G1 plan, Task 13 wireframe). */
internal data class GraphState(
    /** The graph being loaded or shown; null before the first batch. */
    val graph: LaidOutGraph? = null,
    /** Rows the page may read; grows as batches arrive and drives recomposition. */
    val rowCount: Int = 0,
    val widest: Int = 1,
    val phase: Phase = Phase.Idle,
    /** Why the last load failed, with its error code; the rows before it stay. */
    val error: Throwable? = null,
    /** Show output's dialog for [error] is open. */
    val outputShown: Boolean = false,
    /** The graph bar's choices, as `state.json` keeps them. */
    val view: GraphViewState = GraphViewState(),
    /** Refs soloed in the left panel: when any, the branch menu reads "{n} selected". */
    val soloCount: Int = 0,
    /** Ref labels by the commit they point at. */
    val refs: Map<ObjectId, List<RefLabel>> = emptyMap(),
    /** HEAD's commit, for Jump to HEAD and the WIP row's lane. */
    val head: ObjectId? = null,
    /** Changed files when the working tree is dirty: the WIP row; null when it's clean (or bare). */
    val wipFiles: Int? = null,
    val selection: Selection = Selection.None,
    /** A second commit marked with Mod+click, to compare with the selected one (Task 15). */
    val marked: ObjectId? = null,
    val search: SearchState = SearchState(),
    val commitGraph: CommitGraphOffer = CommitGraphOffer.Hidden,
    /** The stashes whose base commit is laid out, by base row then index. */
    val stashes: List<StashPlacement> = emptyList(),
    /** Saved column widths by id; null for a hidden column. */
    val columns: Map<String, Float?> = emptyMap(),
    /** The time "2 h ago" is measured from, in epoch seconds; set when a load starts. */
    val now: Long = 0,
) {
    enum class Phase { Idle, Loading, Loaded, Empty, Failed }

    val table: TableLayout get() = TableLayout(wipFiles != null, stashes, rowCount)

    /** The graph loaded and the filters left nothing. */
    val filteredToNothing: Boolean
        get() = phase == Phase.Loaded && rowCount == 0

    /** Rows are dimmed unless they match: a search is active or some author kinds are left out. */
    val dimming: Boolean
        get() = search.active ||
            view.authorKinds.size < com.srctool.graft.data.app.api.models.GraphAuthorKind.entries.size
}

/** The selected row: the WIP row, a commit's row, or nothing. */
internal sealed interface Selection {
    data object None : Selection

    data object Wip : Selection

    data class Row(val index: Int) : Selection

    /** A stash's row, by its commit. */
    data class Stash(val id: ObjectId) : Selection
}

/** What search looks in (Commit Graph, "Search"); a hash prefix is matched whatever the kind. */
internal enum class SearchKind { MESSAGE, AUTHOR, PATH, CONTENT }

internal data class SearchState(
    val query: String = "",
    val kind: SearchKind = SearchKind.MESSAGE,
    /** Matching commits found so far. */
    val matches: Set<ObjectId> = emptySet(),
    /** Their rows, top first, for Enter and Shift+Enter. */
    val matchRows: List<Int> = emptyList(),
    /** Which of [matchRows] is current, or -1 before the first jump. */
    val current: Int = -1,
    val running: Boolean = false,
    /** Git failed while searching; the matches before it stay. */
    val error: Throwable? = null,
) {
    val active: Boolean get() = query.isNotBlank()
}

/** D8's offer to write a commit-graph file. */
internal enum class CommitGraphOffer { Hidden, Shown, Writing }
