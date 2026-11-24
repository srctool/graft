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
    /** Which tool window is docked where and open, and the docks' sizes (Task 20, UI Design "Layouts"). */
    val panels: PanelLayout = PanelLayout(),
    /** How this repository groups uncommitted changes; the same for every worktree (Changelists, "Mode"). */
    val changesView: ChangesView = ChangesView.INHERIT,
    /** The Changes panel's own choices and the commit box's drafts (Working Copy and Commit). */
    val changes: ChangesPanelState = ChangesPanelState(),
)

/**
 * The Changes panel for one repository. [drafts] is the commit message typed for each branch and not committed yet,
 * keyed by the full ref (`refs/heads/main`), so switching branches brings back what was written there; a changelist's
 * draft lives with the list (Changelists, "Model"), not here.
 */
public data class ChangesPanelState(
    /** Folders as a tree (true) or one flat list. */
    val tree: Boolean = true,
    /** Folders the user folded, by path from the repository root (a chain of single folders is one entry, named by its deepest folder). */
    val collapsed: Set<String> = emptySet(),
    val drafts: Map<String, String> = emptyMap(),
)

/**
 * The window's panel layout for one repository, by tool-window id and dock name (`left`, `right`, `bottom`). An empty
 * layout means the screen's defaults; an id this Graft doesn't know is ignored when the layout is applied.
 */
public data class PanelLayout(
    /** Tool windows moved off their default dock. */
    val sides: Map<String, String> = emptyMap(),
    /** The tool window open in each dock; a dock missing here is closed. */
    val open: Map<String, String> = emptyMap(),
    /** Each dock's size in dp. */
    val sizes: Map<String, Float> = emptyMap(),
    /** True once the user changed anything, so an untouched repository keeps following the defaults. */
    val saved: Boolean = false,
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
