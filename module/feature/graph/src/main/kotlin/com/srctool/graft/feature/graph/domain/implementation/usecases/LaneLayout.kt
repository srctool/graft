// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.domain.implementation.usecases

import com.srctool.graft.data.git.api.models.CommitRow
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.feature.graph.domain.api.models.GraphEdge
import com.srctool.graft.feature.graph.domain.api.models.GraphEdge.Kind
import com.srctool.graft.feature.graph.domain.api.models.GraphRow
import com.srctool.graft.feature.graph.domain.api.usecases.GraphLayout

/**
 * The incremental lane algorithm from the Commit Graph note. `waiting[i]` is the commit lane `i` is waiting for, or
 * null when the lane is free. Lanes never shift sideways: a freed lane is reused by the next line that needs one, which
 * keeps every pass-through line vertical and makes a row depend only on the rows above it.
 *
 * Colors follow branches, not lane indexes: a commit's first parent inherits its color, and each new line (a branch
 * tip, or a merged parent nobody was waiting for yet) takes the next color number. The UI maps numbers to its palette.
 *
 * Rows hold only the lines that touch their node. Lanes passing by are kept once per stretch, as runs of rows in
 * [through]: a large history keeps hundreds of lanes open at once, and an edge per open lane per row took 3.5 GB on
 * the Linux kernel's 1.5M commits (G0 Task 8). Not thread-safe: lay out and query on one thread.
 */
internal class LaneLayout : GraphLayout {
    private val waiting = ArrayList<ObjectId?>()
    private val colors = ArrayList<Int>()

    /** The row whose bottom edge opened each lane's current line. */
    private val opened = ArrayList<Int>()
    private var nextColor = 0

    /** Closed pass-through runs per lane: (first row, last row, color) triples in row order. */
    private val runs = ArrayList<IntArrayBuilder>()

    override var rowCount: Int = 0
        private set

    override var widest: Int = 0
        private set

    // Reused between rows; each row copies the edges it produced.
    private val edges = LongArrayBuilder()

    override fun next(commit: CommitRow): GraphRow {
        val row = rowCount
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

        // Lines arriving at the node; each ends the lane's pass-through run.
        for (i in waiting.indices) {
            if (waiting[i] == id) {
                edges += GraphEdge.pack(Kind.TOP, i, lane, colors[i])
                closeRun(i, row)
                if (i != lane) waiting[i] = null // a second line into this commit ends here
            }
        }

        // The first parent continues in the node's lane with its color; other parents join a lane already waiting
        // for them, or open a new line in a free lane.
        val parents = commit.parents
        if (parents.isEmpty()) {
            set(lane, null, color)
        } else {
            set(lane, parents[0], color)
            opened[lane] = row
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
                    opened[target] = row
                }
                edges += GraphEdge.pack(Kind.BOTTOM, lane, target, colors[target])
            }
        }

        val width = maxOf(waiting.size, lane + 1)
        while (waiting.isNotEmpty() && waiting.last() == null) {
            waiting.removeAt(waiting.lastIndex)
            colors.removeAt(colors.lastIndex)
            opened.removeAt(opened.lastIndex)
        }
        rowCount = row + 1
        if (width > widest) widest = width
        return GraphRow(commit, row, lane, color, width, edges.toArray())
    }

    override fun through(row: Int, lane: Int): Int {
        if (lane < waiting.size && waiting[lane] != null && opened[lane] < row) return colors[lane]
        val lineRuns = runs.getOrNull(lane) ?: return -1
        // The last run starting at or before [row].
        var low = 0
        var high = lineRuns.size / 3 - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lineRuns[mid * 3] <= row) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return if (found >= 0 && lineRuns[found * 3 + 1] >= row) lineRuns[found * 3 + 2] else -1
    }

    /** Records the rows [lane] passed straight through, before its line ends at a node in [row]. */
    private fun closeRun(lane: Int, row: Int) {
        val first = opened[lane] + 1
        if (first > row - 1) return
        while (runs.size <= lane) runs += IntArrayBuilder()
        runs[lane].apply {
            plusAssign(first)
            plusAssign(row - 1)
            plusAssign(colors[lane])
        }
    }

    private fun freeLane(exclude: Int): Int {
        for (i in waiting.indices) if (i != exclude && waiting[i] == null) return i
        waiting += null
        colors += 0
        opened += 0
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

/** A growable `IntArray`. */
private class IntArrayBuilder {
    private var items = IntArray(24)
    var size = 0
        private set

    operator fun plusAssign(value: Int) {
        if (size == items.size) items = items.copyOf(size * 2)
        items[size++] = value
    }

    operator fun get(index: Int): Int = items[index]
}
