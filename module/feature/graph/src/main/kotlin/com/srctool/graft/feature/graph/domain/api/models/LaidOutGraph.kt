// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.domain.api.models

import com.srctool.graft.data.git.api.models.People

/**
 * A commit graph laid out so far: its rows in order, and the lines passing through them. It grows while
 * [com.srctool.graft.feature.graph.domain.api.usecases.LoadGraphUseCase] loads it; read rows below the row count of the
 * latest [GraphLoad], on the thread that collects the load.
 */
internal interface LaidOutGraph {
    /** Rows laid out so far. */
    val size: Int

    /** The widest any row has been so far, in lanes. */
    val widest: Int

    /** The authors and committers rows point into (`CommitRow.authorIndex`); it grows with the rows. */
    val people: People

    operator fun get(index: Int): GraphRow

    /** The color of the line passing straight through [lane] in [row] without touching its node, or -1. */
    fun through(row: Int, lane: Int): Int
}
