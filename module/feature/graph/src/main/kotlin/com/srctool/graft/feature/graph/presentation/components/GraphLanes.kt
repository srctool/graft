// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.srctool.graft.feature.graph.domain.api.models.GraphEdge.Kind
import com.srctool.graft.feature.graph.domain.api.models.GraphRow

internal val LaneWidth = 14.dp
internal const val MAX_DRAWN_LANES = 40

/** Atom One Dark (ADR-22), hard-coded until `:module:core:ui` has Graft's theme. */
internal object GraphColors {
    val Background = Color(0xFF282C34)
    val Foreground = Color(0xFFABB2BF)
    val Muted = Color(0xFF5C6370)
    private val palette =
        listOf(0xFF61AFEF, 0xFF98C379, 0xFFE06C75, 0xFFC678DD, 0xFFE5C07B, 0xFF56B6C2, 0xFFD19A66, 0xFFBE5046)
            .map { Color(it) }

    /** The color for the layout's branch color number. */
    fun branch(index: Int): Color = palette[index.mod(palette.size)]
}

/**
 * One graph row's lanes: the lines passing by, the lines touching the node, then the node on top (hollow for a merge).
 * [through] answers which line passes a lane without touching the node (`GraphLayout.through`). Lanes past
 * [MAX_DRAWN_LANES] are cut off.
 */
@Composable
internal fun GraphLanes(row: GraphRow, through: (row: Int, lane: Int) -> Int, modifier: Modifier) {
    Canvas(modifier) {
        val lane = LaneWidth.toPx()
        val mid = size.height / 2
        val stroke = 2.dp.toPx()
        fun x(i: Int) = lane * i + lane / 2
        for (l in 0 until minOf(row.width, MAX_DRAWN_LANES)) {
            val passing = through(row.index, l)
            if (passing >= 0) drawLine(GraphColors.branch(passing), Offset(x(l), 0f), Offset(x(l), size.height), stroke)
        }
        for (i in 0 until row.edgeCount) {
            val e = row.edge(i)
            if (e.from >= MAX_DRAWN_LANES || e.to >= MAX_DRAWN_LANES) continue
            val color = GraphColors.branch(e.color)
            when (e.kind) {
                Kind.TOP -> drawLine(color, Offset(x(e.from), 0f), Offset(x(e.to), mid), stroke)
                Kind.BOTTOM -> drawLine(color, Offset(x(e.from), mid), Offset(x(e.to), size.height), stroke)
            }
        }
        if (row.lane < MAX_DRAWN_LANES) {
            drawCircle(GraphColors.branch(row.color), radius = 4.dp.toPx(), center = Offset(x(row.lane), mid))
            if (row.commit.isMerge) {
                drawCircle(
                    GraphColors.Background,
                    radius = 2.dp.toPx(),
                    center = Offset(x(row.lane), mid),
                )
            }
        }
    }
}
