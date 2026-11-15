// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.domain

import com.srctool.graft.data.git.api.models.CommitRow

/**
 * One row of the commit graph (Commit Graph note): the commit, the lane its node sits in, and the lines that touch the
 * node. A row is split at the node's height: [GraphEdge.Kind.TOP] segments run from the row's top edge to the node,
 * [GraphEdge.Kind.BOTTOM] segments from the node to the row's bottom edge. Lanes passing straight down without touching
 * the node aren't stored per row; ask [LaneLayout.through]. Lanes never move sideways between rows, so the lanes at the
 * bottom of one row are exactly those at the top of the next.
 *
 * Edges are packed into one array per row, since a 500k-commit history has millions of them.
 */
internal class GraphRow(
    val commit: CommitRow,
    /** The row's position in the graph, from 0 at the top. */
    val index: Int,
    /** The lane of the commit's node, from 0 at the left. */
    val lane: Int,
    /** The node's color: stable along a branch's first-parent line, not tied to the lane index. */
    val color: Int,
    /** Lanes the row uses, so it's at most this many lanes wide. */
    val width: Int,
    private val edges: LongArray,
) {
    val edgeCount: Int get() = edges.size

    fun edge(index: Int): GraphEdge = GraphEdge(edges[index])

    fun edges(): List<GraphEdge> = edges.map(::GraphEdge)
}

/** A line segment in a [GraphRow], packed into a `Long`: kind, start lane, end lane and color. */
@JvmInline
internal value class GraphEdge(private val bits: Long) {
    enum class Kind {
        /** From lane [from] at the row's top to the node in lane [to]: a line arriving at the commit. */
        TOP,

        /** From the node in lane [from] to lane [to] at the row's bottom: a line leaving for a parent. */
        BOTTOM,
    }

    val kind: Kind get() = Kind.entries[(bits ushr KIND_SHIFT).toInt() and 0x3]
    val from: Int get() = ((bits ushr FROM_SHIFT) and LANE_MASK).toInt()
    val to: Int get() = ((bits ushr TO_SHIFT) and LANE_MASK).toInt()
    val color: Int get() = (bits and COLOR_MASK).toInt()

    override fun toString(): String = "$kind $from>$to c$color"

    internal companion object {
        private const val KIND_SHIFT = 62
        private const val FROM_SHIFT = 46
        private const val TO_SHIFT = 30
        private const val LANE_MASK = 0xFFFFL
        private const val COLOR_MASK = 0x3FFF_FFFFL

        /** The widest graph an edge can describe; real histories stay far below it. */
        const val MAX_LANES: Int = 0xFFFF

        fun pack(kind: Kind, from: Int, to: Int, color: Int): Long {
            require(from in 0..MAX_LANES && to in 0..MAX_LANES) { "lane out of range: $from>$to" }
            return (kind.ordinal.toLong() shl KIND_SHIFT) or
                (from.toLong() shl FROM_SHIFT) or
                (to.toLong() shl TO_SHIFT) or
                (color.toLong() and COLOR_MASK)
        }
    }
}
