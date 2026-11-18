// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.details

import com.srctool.graft.data.git.api.models.CommitDetails

/** Commit details (S5, Task 14 wireframe). */
internal data class DetailsState(
    val phase: Phase = Phase.Nothing,
    val details: CommitDetails? = null,
    /** Changed files in the working tree, when the WIP row is selected. */
    val wipFiles: Int = 0,
    val error: Throwable? = null,
    val outputShown: Boolean = false,
    /** Files as a tree of folders, or as a list of paths. Not saved: no setting holds it in G1. */
    val tree: Boolean = true,
    val collapsed: Set<String> = emptySet(),
    val rows: List<FileRow> = emptyList(),
    /** The focused file or folder row. */
    val focus: String? = null,
    /** A second commit marked in the graph: the panel offers to compare it with this one. */
    val marked: com.srctool.graft.data.git.api.models.ObjectId? = null,
    /** "now" for relative times, in epoch seconds. */
    val now: Long = 0,
) {
    enum class Phase { Nothing, Wip, Loading, Loaded, Failed }

    val focusIndex: Int get() = rows.indexOfFirst { it.key == focus }
}

internal sealed interface DetailsEvent {
    data object Opened : DetailsEvent

    data class TreeChosen(val tree: Boolean) : DetailsEvent

    data class Focused(val key: String) : DetailsEvent

    data class Moved(val by: Int) : DetailsEvent

    data object Expand : DetailsEvent

    data object Collapse : DetailsEvent

    /** Enter or a double-click: a file opens its diff, a folder opens or closes. */
    data class Activated(val key: String) : DetailsEvent

    data class ParentChosen(val index: Int) : DetailsEvent

    data object CopyHash : DetailsEvent

    /** Compare with the selected commit as A and the marked one as B. */
    data object CompareMarked : DetailsEvent

    data object Retry : DetailsEvent

    data class OutputShown(val shown: Boolean) : DetailsEvent
}

internal sealed interface DetailsAction {
    data object Start : DetailsAction

    class Local(val change: (DetailsState) -> DetailsState) : DetailsAction

    data class Activate(val key: String) : DetailsAction

    data object Expand : DetailsAction

    data object Collapse : DetailsAction

    data class Parent(val index: Int) : DetailsAction

    data object Copy : DetailsAction

    data object Compare : DetailsAction

    data object Reload : DetailsAction
}

internal sealed interface DetailsEffect {
    data class CopyToClipboard(val text: String) : DetailsEffect
}
