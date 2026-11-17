// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.graph

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.icon.CamoIcon
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.text.CamoText
import com.srctool.camouflage.core.tooltip.CamoTooltip
import com.srctool.graft.core.ui.components.TableCell
import com.srctool.graft.core.ui.components.TableColumnsState
import com.srctool.graft.core.ui.git.AuthorInitials
import com.srctool.graft.core.ui.git.GraphCanvas
import com.srctool.graft.core.ui.git.GraphMarker
import com.srctool.graft.core.ui.git.GraphRowShape
import com.srctool.graft.core.ui.git.LaneEdge
import com.srctool.graft.core.ui.git.LaneEdgeKind
import com.srctool.graft.core.ui.git.RefChip
import com.srctool.graft.core.ui.git.RefLabel
import com.srctool.graft.core.ui.git.SimpleGraphRow
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.core.ui.theme.GraftIcon
import com.srctool.graft.core.ui.theme.graftIcon
import com.srctool.graft.data.app.api.models.GraphAuthorKind
import com.srctool.graft.data.git.api.models.AuthorType
import com.srctool.graft.feature.graph.domain.api.models.GraphEdge
import com.srctool.graft.feature.graph.domain.api.models.GraphRow
import com.srctool.graft.feature.graph.domain.api.models.LaidOutGraph
import com.srctool.graft.feature.graph.domain.api.usecases.GraphMatch

/**
 * A laid-out row as [GraphCanvas] reads it: no copy of its edges, and passing lines asked of the layout. [wipAbove]
 * adds the top half of the line from the WIP row, which the layout doesn't know about, to HEAD's row at the top.
 */
private class RowShape(
    private val row: GraphRow,
    private val graph: LaidOutGraph,
    private val wipAbove: Boolean,
    /** The lane of stashes made on this commit, whose line comes in from above; -1 when it has none. */
    private val stashLane: Int = -1,
) : GraphRowShape {
    override val lane: Int get() = row.lane
    override val color: Int get() = row.color
    override val marker: GraphMarker get() = GraphMarker.COMMIT
    override val width: Int get() = if (stashLane >= 0) maxOf(row.width, stashLane + 1) else row.width
    private val extras = (if (wipAbove) 1 else 0) + (if (stashLane >= 0) 1 else 0)
    override val edgeCount: Int get() = row.edgeCount + extras

    /** 0 for the WIP line, 1 for the stash line, -1 for the layout's own edges. */
    private fun extra(index: Int): Int {
        val k = index - row.edgeCount
        if (k < 0) return -1
        return if (wipAbove && k == 0) 0 else 1
    }

    override fun edgeKind(index: Int): LaneEdgeKind = when {
        extra(index) >= 0 -> LaneEdgeKind.TOP
        row.edge(index).kind == GraphEdge.Kind.TOP -> LaneEdgeKind.TOP
        else -> LaneEdgeKind.BOTTOM
    }

    override fun edgeFrom(index: Int): Int = when (extra(index)) {
        0 -> row.lane
        1 -> stashLane
        else -> row.edge(index).from
    }

    override fun edgeTo(index: Int): Int = if (extra(index) >= 0) row.lane else row.edge(index).to

    override fun edgeColor(index: Int): Int = when (extra(index)) {
        0 -> row.color
        1 -> STASH_COLOR
        else -> row.edge(index).color
    }

    override fun through(lane: Int): Int = graph.through(row.index, lane)
}

@Composable
private fun rowModifier(
    selected: Boolean,
    dimmed: Boolean,
    description: String,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
): Modifier {
    val colors = Camouflage.colors
    return Modifier
        .fillMaxWidth()
        .height(Graft.tokens.density.graphRow)
        .background(if (selected) colors.surfaceContainerHighest else colors.surface)
        .pointerInput(onSelect, onOpen) { detectTapGestures(onTap = { onSelect() }, onDoubleTap = { onOpen() }) }
        .clearAndSetSemantics {
            contentDescription = description
            this.selected = selected
        }.alpha(if (dimmed) DIMMED else 1f)
}

private const val DIMMED = 0.42f

/** Stashes are drawn in the lane palette's gray, apart from the branches' colors; their hollow marker names them. */
private const val STASH_COLOR = 7

/** One commit's row: refs, lanes, message, author, date, SHA, in the table's columns. */
@Composable
internal fun CommitRow(
    state: GraphState,
    graph: LaidOutGraph,
    index: Int,
    columns: TableColumnsState,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    val row = graph[index]
    val commit = row.commit
    val author = graph.people[commit.authorIndex]
    val refs = state.refs[commit.id].orEmpty()
    val match = state.search.active && commit.id in state.search.matches
    val authorShown = GraphMatch.authorType(commit, state.view.authorKinds.mapTo(HashSet()) { it.type() })
    val dimmed =
        (state.search.active && !match) || (state.view.authorKinds.size < GraphAuthorKind.entries.size && !authorShown)
    val age = age(commit.authorTime, state.now.takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000))
    val description =
        rowDescription(commit.id.short(), commit.subject, author.name, age, refs, commit.parents.size, match)
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val wipAbove =
        index == 0 && state.wipFiles != null && commit.id == state.head && state.stashes.none { it.baseRow == 0 }
    val hasStash = state.stashes.any { it.baseRow == index }
    val stashLane = if (hasStash) row.width else -1
    val shape = remember(row, graph, wipAbove, stashLane) { RowShape(row, graph, wipAbove, stashLane) }
    val selected = (state.selection as? Selection.Row)?.index == index

    Row(rowModifier(selected, dimmed, description, onSelect, onOpen), verticalAlignment = Alignment.CenterVertically) {
        for (column in columns.visibleColumns) {
            TableCell(columns, column) {
                when (column.id) {
                    "refs" -> RefsCell(refs, tokens.lanes[row.color])

                    "graph" -> GraphCanvas(
                        shape,
                        Modifier.fillMaxWidth().fillMaxHeight(),
                        background = if (selected) colors.surfaceContainerHighest else colors.surface,
                    )

                    "message" ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            CamoText(
                                commit.subject,
                                modifier = Modifier.weight(1f, fill = false),
                                style = if (match) tokens.denseStrong else tokens.dense,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (commit.authorType == AuthorType.AI_ASSISTED) {
                                CamoIcon(
                                    graftIcon(GraftIcon.AI),
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = colors.onSurfaceVariant,
                                )
                            }
                        }

                    "author" ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            AuthorInitials(author.name, author.email, bot = commit.authorType == AuthorType.BOT)
                            CamoText(author.name, style = tokens.dense, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }

                    "date" ->
                        CamoTooltip(exactTime(commit.authorTime)) {
                            CamoText(shortAge(age), style = tokens.dense, color = colors.onSurfaceVariant, maxLines = 1)
                        }

                    "sha" -> CamoText(
                        commit.id.short(),
                        style = tokens.code.copy(fontSize = tokens.dense.fontSize),
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The WIP row at the top (read-only in G1): a ring on HEAD's lane and the changed-file count. */
@Composable
internal fun WipRow(state: GraphState, columns: TableColumnsState, onSelect: () -> Unit) {
    val files = state.wipFiles ?: return
    val graph = state.graph
    val head = state.head
    // HEAD's row is near the top; its lane is the WIP row's. A line down to it is drawn only when it's the next row.
    val headRow = if (graph != null &&
        head != null
    ) {
        (0 until minOf(state.rowCount, 200)).firstOrNull { graph[it].commit.id == head }
    } else {
        null
    }
    val lane = headRow?.let { graph!![it].lane } ?: 0
    val color = headRow?.let { graph!![it].color } ?: 0
    val shape = remember(lane, color, headRow) {
        SimpleGraphRow(
            lane,
            color,
            if (headRow ==
                0
            ) {
                listOf(LaneEdge(LaneEdgeKind.BOTTOM, lane, lane, color))
            } else {
                emptyList()
            },
            marker = GraphMarker.WIP,
        )
    }
    val description = graftText("graph.wip.label", "Uncommitted changes, {count} files", "count" to files)
    val selected = state.selection == Selection.Wip
    val colors = Camouflage.colors
    Row(
        rowModifier(selected, dimmed = false, description, onSelect, onOpen = {
        }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (column in columns.visibleColumns) {
            TableCell(columns, column) {
                when (column.id) {
                    "graph" -> GraphCanvas(
                        shape,
                        Modifier.fillMaxWidth().fillMaxHeight(),
                        background = if (selected) colors.surfaceContainerHighest else colors.surface,
                    )

                    "message" ->
                        CamoText(
                            graftText("graph.wip", "Uncommitted changes ({count} files)", "count" to files),
                            style = Graft.tokens.dense.copy(fontWeight = FontWeight.Medium),
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                        )
                }
            }
        }
    }
}

/** Up to two chips, then `+n`; screen readers hear every ref in the row's label. */
@Composable
private fun RefsCell(refs: List<RefLabel>, color: androidx.compose.ui.graphics.Color) {
    if (refs.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        refs.take(2).forEach { RefChip(it, color, Modifier.weight(1f, fill = false)) }
        if (refs.size > 2) {
            CamoText(
                graftText("graph.row.more", "+{count}", "count" to refs.size - 2),
                modifier = Modifier.padding(start = 2.dp),
                style = Graft.tokens.dense,
                color = Camouflage.colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun shortAge(age: Age): String = when (age) {
    Age.JustNow -> graftText("graph.age.now", "just now")
    is Age.Minutes -> graftText("graph.age.minutes", "{count} min ago", "count" to age.count)
    is Age.Hours -> graftText("graph.age.hours", "{count} h ago", "count" to age.count)
    is Age.Days -> graftText("graph.age.days", "{count} d ago", "count" to age.count)
    is Age.Date -> age.text
}

@Composable
private fun longAge(age: Age): String = when (age) {
    Age.JustNow -> graftText("graph.age.now", "just now")
    is Age.Minutes -> graftText("graph.ageLong.minutes", "{count} minutes ago", "count" to age.count)
    is Age.Hours -> graftText("graph.ageLong.hours", "{count} hours ago", "count" to age.count)
    is Age.Days -> graftText("graph.ageLong.days", "{count} days ago", "count" to age.count)
    is Age.Date -> age.text
}

/**
 * The row's one label (Accessibility, "Graph"): "Commit a1b2c3d, Fix login timeout, by Dwiki Riyadi, 2 hours ago,
 * refs main ⇄ origin/main and v1.4, merge of 2 parents, matches search".
 */
@Composable
internal fun rowDescription(
    sha: String,
    subject: String,
    author: String,
    age: Age,
    refs: List<RefLabel>,
    parents: Int,
    match: Boolean,
): String {
    val parts = mutableListOf(
        graftText(
            "graph.row.commit",
            "Commit {sha}, {subject}, by {author}, {age}",
            "sha" to sha,
            "subject" to subject,
            "author" to author,
            "age" to longAge(age),
        ),
    )
    if (refs.isNotEmpty()) {
        val names = refs.map { r -> r.mergedRemote?.let { "${r.name} ⇄ $it" } ?: r.name }
        val joined =
            if (names.size == 1) {
                names[0]
            } else {
                graftText(
                    "graph.row.and",
                    "{first} and {last}",
                    "first" to names.dropLast(1).joinToString(", "),
                    "last" to names.last(),
                )
            }
        parts += graftText("graph.row.refs", "refs {names}", "names" to joined)
    }
    if (parents > 1) parts += graftText("graph.row.merge", "merge of {count} parents", "count" to parents)
    if (match) parts += graftText("graph.row.match", "matches search")
    return parts.joinToString(", ")
}

/**
 * A stash's row, just above its base commit (Commit Graph: "a hollow node hanging off its base commit"): the lines
 * passing the base commit's top pass here too, and the stash's own line, in a lane right of the base row, runs down
 * and curves into the base commit.
 */
@Composable
internal fun StashRow(
    state: GraphState,
    graph: LaidOutGraph,
    placement: StashPlacement,
    columns: TableColumnsState,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    val entry = placement.entry
    val base = graph[placement.baseRow]
    val lane = base.width
    val group = state.stashes.filter { it.baseRow == placement.baseRow }
    val first = group.firstOrNull() == placement
    val shape =
        remember(placement, base, first) {
            // What runs past the base commit's top runs past this row too.
            val passing = HashMap<Int, Int>()
            for (l in 0 until base.width) graph.through(base.index, l).takeIf { it >= 0 }?.let { passing[l] = it }
            for (i in 0 until base.edgeCount) {
                val e = base.edge(i)
                if (e.kind == GraphEdge.Kind.TOP) passing[e.from] = e.color
            }
            val edges = buildList {
                if (!first) add(LaneEdge(LaneEdgeKind.TOP, lane, lane, STASH_COLOR))
                add(LaneEdge(LaneEdgeKind.BOTTOM, lane, lane, STASH_COLOR))
            }
            SimpleGraphRow(lane, STASH_COLOR, edges, passing, GraphMarker.STASH)
        }
    val age = age(entry.time, state.now.takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000))
    val description =
        listOfNotNull(
            graftText(
                "graph.stash.label",
                "Stash {index}, {message}",
                "index" to entry.index,
                "message" to entry.message,
            ),
            entry.branch?.let { graftText("graph.stash.branch", "made on {branch}", "branch" to it) },
            longAge(age),
        ).joinToString(", ")
    val selected = (state.selection as? Selection.Stash)?.id == entry.id
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val dimmed = state.search.active && entry.id !in state.search.matches
    Row(rowModifier(selected, dimmed, description, onSelect, onOpen), verticalAlignment = Alignment.CenterVertically) {
        for (column in columns.visibleColumns) {
            TableCell(columns, column) {
                when (column.id) {
                    "refs" -> CamoText(
                        "stash@{${entry.index}}",
                        style = tokens.code.copy(fontSize = tokens.dense.fontSize),
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )

                    "graph" -> GraphCanvas(
                        shape,
                        Modifier.fillMaxWidth().fillMaxHeight(),
                        background = if (selected) colors.surfaceContainerHighest else colors.surface,
                    )

                    "message" -> CamoText(
                        entry.message,
                        style = tokens.dense.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    "date" -> CamoTooltip(exactTime(entry.time)) {
                        CamoText(shortAge(age), style = tokens.dense, color = colors.onSurfaceVariant, maxLines = 1)
                    }

                    "sha" -> CamoText(
                        entry.id.short(),
                        style = tokens.code.copy(fontSize = tokens.dense.fontSize),
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
