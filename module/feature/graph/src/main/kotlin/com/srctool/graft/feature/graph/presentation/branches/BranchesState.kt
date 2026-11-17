// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.branches

/** The left panel (Task 14 wireframe). */
internal data class BranchesState(
    val rows: List<PanelRow> = emptyList(),
    /** The focused row's key; survives rebuilds as refs come and go. */
    val focus: String? = null,
    val phase: Phase = Phase.Loading,
    /** Why the refs couldn't be read, with its code. */
    val error: Throwable? = null,
    val query: String = "",
    /** The row whose context menu is open (Shift+F10 or the menu key). */
    val menuFor: String? = null,
) {
    enum class Phase { Loading, Ready, Failed }

    val focusIndex: Int get() = rows.indexOfFirst { it.key == focus }
}
