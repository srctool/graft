// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.domain.api.usecases

import com.srctool.graft.data.git.api.models.CommitRow
import com.srctool.graft.feature.graph.domain.api.models.GraphRow

/**
 * Lays out the commit graph's lanes as commits stream in (Commit Graph note, "Layout algorithm"): one [GraphRow] per
 * commit, in `--topo-order` (children before parents). Stateful, one per graph, and not thread-safe: lay out and
 * query on one thread.
 */
internal interface GraphLayout {
    /** Rows laid out so far. */
    val rowCount: Int

    /** The widest any row has been so far. */
    val widest: Int

    /** Lays out the next commit and returns its row. */
    fun next(commit: CommitRow): GraphRow

    /**
     * The color of the line passing straight through [lane] in [row] without touching its node, or -1 if none does.
     * [row] must already be laid out.
     */
    fun through(row: Int, lane: Int): Int
}
