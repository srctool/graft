// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.app.api.models

/**
 * The graph's per-repository choices, in `$GIT_COMMON_DIR/graft/state.json` v1 (Storage Formats), shared by every
 * worktree of the repository. Refs are full names (`refs/heads/main`).
 */
public data class RepositoryUiState(
    val hiddenRefs: Set<String> = emptySet(),
    /** When not empty, the graph shows only these refs' history. */
    val soloRefs: Set<String> = emptySet(),
    /** Kept at the top of the left panel's lists. */
    val pinnedRefs: Set<String> = emptySet(),
)
