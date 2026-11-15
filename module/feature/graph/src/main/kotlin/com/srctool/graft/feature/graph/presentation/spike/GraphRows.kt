// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.spike

import com.srctool.graft.data.git.api.models.CommitRow
import com.srctool.graft.feature.graph.domain.api.models.GraphRow
import com.srctool.graft.feature.graph.domain.api.usecases.GraphLayout

/**
 * The laid-out rows, appended by the view model as `git log` streams. The page reads rows below
 * [GraphSpikeState.rowCount]; both run on the main thread, so a read never meets a half-made append.
 */
internal class GraphRows(private val layout: GraphLayout) {
    private val rows = ArrayList<GraphRow>(1 shl 20)

    val widest: Int get() = layout.widest
    val size: Int get() = rows.size

    operator fun get(index: Int): GraphRow = rows[index]

    /** The color of the line passing through [lane] in [row], or -1. */
    fun through(row: Int, lane: Int): Int = layout.through(row, lane)

    fun add(commit: CommitRow) {
        rows += layout.next(commit)
    }
}
