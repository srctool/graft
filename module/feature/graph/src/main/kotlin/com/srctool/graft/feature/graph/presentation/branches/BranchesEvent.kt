// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.branches

internal sealed interface BranchesEvent {
    data object Opened : BranchesEvent

    data class Focused(val key: String) : BranchesEvent

    /** ↑ (-1) / ↓ (+1). */
    data class Moved(val by: Int) : BranchesEvent

    /** →: expands, or goes to the first child. */
    data object Expand : BranchesEvent

    /** ←: collapses, or goes to the parent. */
    data object Collapse : BranchesEvent

    /** Enter or a click: selects the row's commit in the graph, or opens and closes a header or folder. */
    data class Activated(val key: String) : BranchesEvent

    data class HideToggled(val refName: String) : BranchesEvent

    data class SoloToggled(val refName: String) : BranchesEvent

    data class PinToggled(val refName: String) : BranchesEvent

    data class Searched(val query: String) : BranchesEvent

    data class MenuShown(val key: String?) : BranchesEvent

    data class CopyName(val name: String) : BranchesEvent

    data object Retry : BranchesEvent
}
