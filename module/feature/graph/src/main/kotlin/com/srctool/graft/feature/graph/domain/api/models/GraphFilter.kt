// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.domain.api.models

import com.srctool.graft.data.git.api.models.LogScope
import com.srctool.graft.data.git.api.models.ObjectId

/**
 * The left panel's hide and solo choices (Commit Graph, "Left panel"), as the repository's `state.json` keeps them.
 * Refs are full names.
 */
internal data class GraphFilter(val hiddenRefs: Set<String> = emptySet(), val soloRefs: Set<String> = emptySet()) {
    /**
     * The refs git walks: the solo refs that aren't also hidden, or every ref but the hidden ones when nothing is
     * soloed (or every soloed ref is hidden too).
     */
    val scope: LogScope
        get() {
            val solo = soloRefs - hiddenRefs
            return if (solo.isNotEmpty()) LogScope.Only(solo) else LogScope.All(hiddenRefs)
        }

    /**
     * Only the refs that still exist: a branch deleted outside Graft stays in `state.json`, and git fails a log that
     * names a ref it can't find.
     */
    fun prunedTo(existing: Set<String>): GraphFilter = GraphFilter(
        hiddenRefs intersect existing,
        soloRefs intersect existing,
    )
}

/** One step of a search: more matching commits to highlight, the end, or a failure. */
internal sealed interface GraphSearch {
    /** Commits that match, newest first, to add to those already found. */
    data class Found(val ids: List<ObjectId>) : GraphSearch

    /** The search finished with [total] matches. */
    data class Done(val total: Int) : GraphSearch

    /** Git failed after the matches already sent; [error] carries its code. */
    data class Failed(val error: Throwable) : GraphSearch
}

/** Compare's header (Compare Branches): "[compare] is [ahead] ahead and [behind] behind [base]". */
internal data class CompareSummary(
    val base: String,
    val compare: String,
    /** Commits in [compare] and not in [base]. */
    val ahead: Int,
    /** Commits in [base] and not in [compare]. */
    val behind: Int,
    /** Where they forked; null when the histories are unrelated. */
    val mergeBase: ObjectId?,
)
