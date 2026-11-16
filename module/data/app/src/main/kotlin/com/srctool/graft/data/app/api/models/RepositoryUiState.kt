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
    /** The graph's view choices (ADR-27). */
    val graph: GraphViewState = GraphViewState(),
    /** The graph table's column widths in dp by column id; null for a hidden column (ADR-27). */
    val columns: Map<String, Float?> = emptyMap(),
)

/** Which history the graph walks when nothing is soloed: every branch, or the current one's. */
public enum class GraphBranches { ALL, CURRENT }

/** Who wrote a commit, for the graph's author filter; the data layer's `AuthorType`, in Graft's own app files. */
public enum class GraphAuthorKind { PERSON, AI_ASSISTED, BOT }

/** The graph bar's choices (ADR-27); the defaults are what a repository opened for the first time shows. */
public data class GraphViewState(
    val branches: GraphBranches = GraphBranches.ALL,
    val hideRemotes: Boolean = false,
    val firstParent: Boolean = false,
    /** The authors whose commits are shown at full strength; the others are dimmed. */
    val authorKinds: Set<GraphAuthorKind> = GraphAuthorKind.entries.toSet(),
    /** D8's commit-graph offer was shown and answered, so it isn't shown again. */
    val commitGraphOffered: Boolean = false,
)
