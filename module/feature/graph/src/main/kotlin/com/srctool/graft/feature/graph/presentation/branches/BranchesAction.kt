// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.branches

internal sealed interface BranchesAction {
    data object Start : BranchesAction

    /** A change to the panel's own state, applied to the state as it is when it runs. */
    class Local(val change: (BranchesState) -> BranchesState) : BranchesAction

    data class Activate(val key: String) : BranchesAction

    data object Expand : BranchesAction

    data object Collapse : BranchesAction

    /** Changes the saved hide, solo or pin choices. */
    class Choose(
        val change: (
            com.srctool.graft.data.app.api.models.RepositoryUiState,
        ) -> com.srctool.graft.data.app.api.models.RepositoryUiState,
    ) : BranchesAction

    data class Copy(val text: String) : BranchesAction

    data object Retry : BranchesAction
}
