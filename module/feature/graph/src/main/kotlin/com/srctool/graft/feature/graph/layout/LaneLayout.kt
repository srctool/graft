// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.layout

import com.srctool.graft.data.git.api.models.CommitRow
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.feature.graph.layout.GraphEdge.Kind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Lays out the graph as commits stream in, one [GraphRow] per commit, so the first rows draw before `git log`
 * finishes. The commits must be in `--topo-order` (children before parents). Each collection starts a new layout.
 */
internal fun Flow<CommitRow>.laidOut(): Flow<GraphRow> = flow {
    val lanes = LaneLayout()
    collect { emit(lanes.next(it)) }
}

/**
 * The incremental lane algorithm from the Commit Graph note. `waiting[i]` is the commit lane `i` is waiting for, or
 * null when the lane is free. Lanes never shift sideways: a freed lane is reused by the next line that needs one, which
 * keeps every pass-through line vertical and makes a row depend only on the rows above it.
 *
 * Colors follow branches, not lane indexes: a commit's first parent inherits its color, and each new line (a branch
 * tip, or a merged parent nobody was waiting for yet) takes the next color number. The UI maps numbers to its palette.
 */
internal class LaneLayout {
    private val waiting = ArrayList<ObjectId?>()
    private val colors = ArrayList<Int>()
    private var nextColor = 0

    // Reused between rows; each row copies the edges it produced.
    private val edges = LongArrayBuilder()

    fun next(commit: CommitRow): GraphRow {
        edges.clear()
        val id = commit.id

        // The node goes in the first lane waiting for this commit, or the first free lane for a new branch tip.
        var lane = -1
        for (i in waiting.indices) {
            if (waiting[i] == id) {
                lane = i
                break
            }
        }
        val color: Int
        if (lane >= 0) {
            color = colors[lane]
        } else {
            lane = freeLane(exclude = -1)
            color = nextColor++
        }

        // Lines arriving at the node, and lanes passing by.
        for (i in waiting.indices) {
            val awaited = waiting[i] ?: continue
            if (awaited == id) {
                edges += GraphEdge.pack(Kind.TOP, i, lane, colors[i])
                if (i != lane) waiting[i] = null // a second line into this commit ends here
            } else {
                edges += GraphEdge.pack(Kind.THROUGH, i, i, colors[i])
            }
        }

        // The first parent continues in the node's lane with its color; other parents join a lane already waiting
        // for them, or open a new line in a free lane.
        val parents = commit.parents
        if (parents.isEmpty()) {
            set(lane, null, color)
        } else {
            set(lane, parents[0], color)
            edges += GraphEdge.pack(Kind.BOTTOM, lane, lane, color)
            for (p in 1 until parents.size) {
                val parent = parents[p]
                var target = -1
                for (i in waiting.indices) {
                    if (i != lane && waiting[i] == parent) {
                        target = i
                        break
                    }
                }
                if (target < 0) {
                    target = freeLane(exclude = lane)
                    set(target, parent, nextColor++)
                }
                edges += GraphEdge.pack(Kind.BOTTOM, lane, target, colors[target])
            }
        }

        val width = maxOf(waiting.size, lane + 1)
        while (waiting.isNotEmpty() && waiting.last() == null) {
            waiting.removeAt(waiting.lastIndex)
            colors.removeAt(colors.lastIndex)
        }
        return GraphRow(commit, lane, color, width, edges.toArray())
    }

    private fun freeLane(exclude: Int): Int {
        for (i in waiting.indices) if (i != exclude && waiting[i] == null) return i
        waiting += null
        colors += 0
        check(waiting.size <= GraphEdge.MAX_LANES) { "graph wider than ${GraphEdge.MAX_LANES} lanes" }
        return waiting.lastIndex
    }

    private fun set(lane: Int, awaited: ObjectId?, color: Int) {
        waiting[lane] = awaited
        colors[lane] = color
    }
}

/** A growable `LongArray`, so building a row's edges doesn't box them. */
private class LongArrayBuilder {
    private var items = LongArray(16)
    private var size = 0

    operator fun plusAssign(value: Long) {
        if (size == items.size) items = items.copyOf(size * 2)
        items[size++] = value
    }

    fun clear() {
        size = 0
    }

    fun toArray(): LongArray = items.copyOf(size)
}
