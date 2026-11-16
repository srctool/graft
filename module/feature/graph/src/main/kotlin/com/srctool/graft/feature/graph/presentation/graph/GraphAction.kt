// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.graph

import com.srctool.graft.data.app.api.models.GraphViewState

/** The work the view model does for an event. */
internal sealed interface GraphAction {
    /** Reads the saved choices, follows the repository session, and loads. */
    data object Start : GraphAction

    data object Reload : GraphAction

    /**
     * Applies [change] to the bar's choices as they are when it runs (so quick toggles don't undo each other), saves
     * them, and reloads (a filter git applies) or not (the author filter only dims).
     */
    class ChangeView(val reload: Boolean, val change: (GraphViewState) -> GraphViewState) : GraphAction

    data object ClearSolo : GraphAction

    data class Search(val query: String, val kind: SearchKind) : GraphAction

    data class Select(val selection: Selection) : GraphAction

    /** A key that moves the selection: [pick] reads the state as it is when this runs, so repeats add up. */
    class SelectBy(val pick: (GraphState) -> Selection?) : GraphAction

    /** Alt+→: the selected merge's next other parent. */
    data object OtherParent : GraphAction

    /** Copies the selected commit's full hash, as selected when this runs. */
    data object CopyHash : GraphAction

    /** Opens the selected commit, as selected when this runs. */
    data object Open : GraphAction

    data object WriteCommitGraph : GraphAction

    data object DeclineCommitGraph : GraphAction

    data class SaveColumns(val columns: Map<String, Float?>) : GraphAction

    data class ShowOutput(val shown: Boolean) : GraphAction
}
