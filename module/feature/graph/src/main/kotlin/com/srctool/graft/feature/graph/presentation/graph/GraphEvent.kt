// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.graph

import com.srctool.graft.data.app.api.models.GraphAuthorKind
import com.srctool.graft.data.app.api.models.GraphBranches

/** What happens on the graph page. */
internal sealed interface GraphEvent {
    data object Opened : GraphEvent

    data class BranchesChosen(val branches: GraphBranches) : GraphEvent

    /** "Show all branches" when the filter left nothing: every branch, and the left panel's solo cleared. */
    data object ShowAllBranches : GraphEvent

    data object HideRemotesToggled : GraphEvent

    data object FirstParentToggled : GraphEvent

    data class AuthorKindToggled(val kind: GraphAuthorKind) : GraphEvent

    data class SearchTyped(val query: String) : GraphEvent

    data class SearchKindChosen(val kind: SearchKind) : GraphEvent

    data object NextMatch : GraphEvent

    data object PreviousMatch : GraphEvent

    data object SearchCleared : GraphEvent

    data class Selected(val selection: Selection) : GraphEvent

    /** ↑ / ↓ / PageUp / PageDown / Home / End: [rows] up (negative) or down, clamped to the table. */
    data class MovedBy(val rows: Int) : GraphEvent

    data object FirstParent : GraphEvent

    data object FirstChild : GraphEvent

    data object OtherParent : GraphEvent

    /** `[` (-1) and `]` (+1). */
    data class NextRef(val direction: Int) : GraphEvent

    data object JumpToHead : GraphEvent

    data object OpenSelected : GraphEvent

    data object CopyHash : GraphEvent

    data object Retry : GraphEvent

    data class OutputShown(val shown: Boolean) : GraphEvent

    data object SpeedUpAccepted : GraphEvent

    data object SpeedUpDeclined : GraphEvent

    data class ColumnsChanged(val columns: Map<String, Float?>) : GraphEvent
}
