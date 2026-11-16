// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.git

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.graft.core.ui.theme.Graft

/** How a graph node is drawn (UI Design, "Graph style"); shapes, not only colors, tell them apart (Accessibility). */
public enum class GraphMarker {
    /** A commit, merges included: a filled dot. */
    COMMIT,

    /** A stash hanging off its base commit: a hollow dot. */
    STASH,

    /** A shelf or a local-history snapshot: a dashed circle. */
    SHELF,

    /** Uncommitted changes, the row at the top on HEAD's lane: a ring around a small dot. */
    WIP,
}

/** Which half of a row a [GraphRowShape] line is in: from the row's top to the node, or from the node to the bottom. */
public enum class LaneEdgeKind { TOP, BOTTOM }

/**
 * What [GraphCanvas] draws for one row, read without allocating since it's asked every frame for every visible row. The
 * graph feature adapts its laid-out rows to it; [SimpleGraphRow] is a plain implementation.
 *
 * Colors are lane color indexes ([com.srctool.graft.core.ui.theme.GraftLanes]); lanes count from 0 at the left.
 */
public interface GraphRowShape {
    /** The node's lane. */
    public val lane: Int

    /** The node's color index. */
    public val color: Int

    public val marker: GraphMarker

    /** Lanes the row uses. */
    public val width: Int

    public val edgeCount: Int

    public fun edgeKind(index: Int): LaneEdgeKind

    /** The lane a line starts in: at the row's top for [LaneEdgeKind.TOP], the node's lane for [LaneEdgeKind.BOTTOM]. */
    public fun edgeFrom(index: Int): Int

    /** The lane a line ends in: the node's lane for [LaneEdgeKind.TOP], at the row's bottom for [LaneEdgeKind.BOTTOM]. */
    public fun edgeTo(index: Int): Int

    public fun edgeColor(index: Int): Int

    /** The color of the line passing straight down [lane] without touching the node, or -1 when none does. */
    public fun through(lane: Int): Int
}

/** A line of a [SimpleGraphRow]. */
public data class LaneEdge(val kind: LaneEdgeKind, val from: Int, val to: Int, val color: Int)

/** A [GraphRowShape] from plain values, for previews, tests and small graphs. */
public data class SimpleGraphRow(
    override val lane: Int,
    override val color: Int,
    val edges: List<LaneEdge> = emptyList(),
    /** Lines passing straight down: lane to color. */
    val passing: Map<Int, Int> = emptyMap(),
    override val marker: GraphMarker = GraphMarker.COMMIT,
) : GraphRowShape {
    override val width: Int =
        maxOf(lane, edges.maxOfOrNull { maxOf(it.from, it.to) } ?: 0, passing.keys.maxOrNull() ?: 0) + 1
    override val edgeCount: Int get() = edges.size

    override fun edgeKind(index: Int): LaneEdgeKind = edges[index].kind

    override fun edgeFrom(index: Int): Int = edges[index].from

    override fun edgeTo(index: Int): Int = edges[index].to

    override fun edgeColor(index: Int): Int = edges[index].color

    override fun through(lane: Int): Int = passing[lane] ?: -1
}

/** Lanes past this are cut off: a graph that wide can't be read anyway, and drawing it costs every frame. */
public const val MAX_DRAWN_LANES: Int = 40

/** The distance between lane centers. */
public val GraphLaneSpacing: Dp = 14.dp

/**
 * One graph row's lanes (UI Design, "Graph style"): 2 px lines that turn with rounded corners into and out of the
 * node, then the node's marker on top, in the theme's lane colors. Decorative to screen readers: the row around it
 * says what the commit is (Accessibility, "Graph").
 *
 * [background] is what a hollow marker is filled with: the row's background, so a line doesn't show through it.
 */
@Composable
public fun GraphCanvas(
    row: GraphRowShape,
    modifier: Modifier = Modifier,
    laneSpacing: Dp = GraphLaneSpacing,
    background: Color = Camouflage.colors.surface,
) {
    val tokens = Graft.tokens
    val lanes = tokens.lanes
    val strokeWidth = tokens.density.laneWidth
    val dot = tokens.density.commitDot
    val path = remember { Path() }
    Canvas(modifier.clearAndSetSemantics {}) {
        val spacing = laneSpacing.toPx()
        val stroke = strokeWidth.toPx()
        val mid = size.height / 2
        // The corner radius fits half a lane and half a row, so neighbouring turns never cross.
        val radius = minOf(spacing / 2, mid)
        fun x(lane: Int) = spacing * lane + spacing / 2

        val drawn = minOf(row.width, MAX_DRAWN_LANES)
        for (l in 0 until drawn) {
            val passing = row.through(l)
            if (passing >= 0) drawLine(lanes[passing], Offset(x(l), 0f), Offset(x(l), size.height), stroke)
        }
        for (i in 0 until row.edgeCount) {
            val from = row.edgeFrom(i)
            val to = row.edgeTo(i)
            if (from >= MAX_DRAWN_LANES || to >= MAX_DRAWN_LANES) continue
            val color = lanes[row.edgeColor(i)]
            path.reset()
            when (row.edgeKind(i)) {
                LaneEdgeKind.TOP -> path.arrive(x(from), x(to), mid, radius)
                LaneEdgeKind.BOTTOM -> path.leave(x(from), x(to), mid, size.height, radius)
            }
            drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        if (row.lane < MAX_DRAWN_LANES) {
            drawMarker(row.marker, lanes[row.color], Offset(x(row.lane), mid), dot.toPx() / 2, stroke, background)
        }
    }
}

/** A line from lane [fromX] at the top down into the node at [toX]: straight, or down then a corner then across. */
private fun Path.arrive(fromX: Float, toX: Float, mid: Float, radius: Float) {
    moveTo(fromX, 0f)
    if (fromX == toX) {
        lineTo(toX, mid)
        return
    }
    val left = toX < fromX
    lineTo(fromX, mid - radius)
    val cornerLeft = if (left) fromX - 2 * radius else fromX
    arcTo(
        Rect(cornerLeft, mid - 2 * radius, cornerLeft + 2 * radius, mid),
        if (left) 0f else 180f,
        if (left) 90f else -90f,
        false,
    )
    lineTo(toX, mid)
}

/** A line from the node at [fromX] to lane [toX] at the bottom: straight, or across then a corner then down. */
private fun Path.leave(fromX: Float, toX: Float, mid: Float, bottom: Float, radius: Float) {
    moveTo(fromX, mid)
    if (fromX == toX) {
        lineTo(toX, bottom)
        return
    }
    val right = toX > fromX
    lineTo(if (right) toX - radius else toX + radius, mid)
    val cornerLeft = if (right) toX - 2 * radius else toX
    // Angles run clockwise from 3 o'clock: the corner starts at the top of its circle either way.
    arcTo(Rect(cornerLeft, mid, cornerLeft + 2 * radius, mid + 2 * radius), 270f, if (right) 90f else -90f, false)
    lineTo(toX, bottom)
}

private fun DrawScope.drawMarker(
    marker: GraphMarker,
    color: Color,
    center: Offset,
    radius: Float,
    stroke: Float,
    background: Color,
) {
    when (marker) {
        GraphMarker.COMMIT -> drawCircle(color, radius, center)

        GraphMarker.STASH -> {
            drawCircle(background, radius, center)
            drawCircle(color, radius - stroke / 2, center, style = Stroke(stroke))
        }

        GraphMarker.SHELF -> {
            drawCircle(background, radius, center)
            val dash = PathEffect.dashPathEffect(floatArrayOf(stroke * 1.5f, stroke))
            drawCircle(color, radius - stroke / 2, center, style = Stroke(stroke, pathEffect = dash))
        }

        GraphMarker.WIP -> {
            drawCircle(background, radius + stroke, center)
            drawCircle(color, radius + stroke / 2, center, style = Stroke(stroke))
            drawCircle(color, radius / 2.5f, center)
        }
    }
}
