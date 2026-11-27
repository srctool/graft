// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.graph

import com.srctool.graft.core.ui.git.RefLabel
import com.srctool.graft.core.ui.git.RefLabelKind
import com.srctool.graft.data.git.api.models.CommitRow
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.Ref
import com.srctool.graft.data.git.api.models.RefKind
import com.srctool.graft.feature.graph.domain.api.models.LaidOutGraph
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The ref labels of each commit (Commit Graph, "What appears on the graph"): the checked-out branch first, then local
 * branches, remote branches and tags, each by name. A local branch at the same commit as its upstream carries it
 * (`main ⇄ origin/main`) and the remote gets no chip of its own. Symbolic refs (`origin/HEAD`), the stash and Graft's
 * own refs aren't labels: the graph draws stashes and shelves as nodes.
 */
internal fun refLabels(refs: List<Ref>): Map<ObjectId, List<RefLabel>> {
    val shown = refs.filter { it.symbolicTarget == null && it.kind in LABELLED }
    return shown.groupBy { it.commit }.mapValues { (_, atCommit) ->
        val remotes = atCommit.filter { it.kind == RefKind.REMOTE_BRANCH }.associateBy { it.name }
        val merged = HashSet<String>()
        val locals =
            atCommit
                .filter { it.kind == RefKind.LOCAL_BRANCH }
                .sortedWith(compareBy<Ref>({ !it.isHead }, { it.shortName }))
                .map { local ->
                    val upstream = local.upstream?.let(remotes::get)
                    if (upstream != null) merged += upstream.name
                    RefLabel(
                        local.shortName,
                        RefLabelKind.LOCAL,
                        head = local.isHead,
                        mergedRemote = upstream?.shortName,
                        ahead = local.ahead ?: 0,
                        behind = local.behind ?: 0,
                    )
                }
        val remoteLabels =
            remotes.values
                .filter { it.name !in merged }
                .sortedBy { it.shortName }
                .map { RefLabel(it.shortName, RefLabelKind.REMOTE) }
        val tags =
            atCommit.filter { it.kind == RefKind.TAG }.sortedBy { it.shortName }
                .map { RefLabel(it.shortName, RefLabelKind.TAG) }
        locals + remoteLabels + tags
    }
}

private val LABELLED = setOf(RefKind.LOCAL_BRANCH, RefKind.REMOTE_BRANCH, RefKind.TAG)

/**
 * Where the graph's keys move the selection (Accessibility, "Graph"), over rows `0 until count` read by [row]. Rows
 * are in topological order, so a parent is always below its children: a parent is searched downwards, a child
 * upwards, and neither needs an index of every commit (a 500k-row map would cost tens of megabytes).
 */
internal class GraphMoves(private val count: Int, private val row: (Int) -> CommitRow) {
    /** Alt+↓: the row of [from]'s first parent, or null when it has none in the graph (a root, or filtered out). */
    fun firstParent(from: Int): Int? = row(from).parents.firstOrNull()?.let { below(from, it) }

    /** Alt+↑: the nearest row above [from] whose commit has [from]'s commit as a parent. */
    fun firstChild(from: Int): Int? {
        val id = row(from).id
        for (i in from - 1 downTo 0) if (id in row(i).parents) return i
        return null
    }

    /**
     * Alt+→: the row of a merge's [step]th other parent (cycling through them); null for a commit with one parent or
     * when the parent isn't in the graph.
     */
    fun otherParent(from: Int, step: Int): Int? {
        val others = row(from).parents.drop(1)
        if (others.isEmpty()) return null
        return below(from, others[Math.floorMod(step, others.size)])
    }

    /** `[` / `]`: the next row above ([direction] -1) or below (+1) that has a ref label. */
    fun nextRef(from: Int, direction: Int, hasRef: (Int) -> Boolean): Int? {
        var i = from + direction
        while (i in 0 until count) {
            if (hasRef(i)) return i
            i += direction
        }
        return null
    }

    /** The row showing commit [id], searched from the top; null when it isn't laid out (yet). */
    fun rowOf(id: ObjectId): Int? = (0 until count).firstOrNull { row(it).id == id }

    private fun below(from: Int, id: ObjectId): Int? = (from + 1 until count).firstOrNull { row(it).id == id }
}

/** How long ago something was, in the units UX Writing uses: under a week relative, then the OS's date. */
internal sealed interface Age {
    data object JustNow : Age

    data class Minutes(val count: Long) : Age

    data class Hours(val count: Long) : Age

    data class Days(val count: Long) : Age

    /** A week or more ago: the date in the OS's medium format. */
    data class Date(val text: String) : Age
}

internal fun age(
    epochSeconds: Long,
    nowSeconds: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): Age {
    val seconds = nowSeconds - epochSeconds
    return when {
        seconds < 60 -> Age.JustNow
        seconds < 3_600 -> Age.Minutes(seconds / 60)
        seconds < 86_400 -> Age.Hours(seconds / 3_600)
        seconds < 7 * 86_400 -> Age.Days(seconds / 86_400)
        else -> Age.Date(dateFormat(locale).format(Instant.ofEpochSecond(epochSeconds).atZone(zone)))
    }
}

/** The exact time, for the date's tooltip. */
internal fun exactTime(
    epochSeconds: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
    .format(Instant.ofEpochSecond(epochSeconds).atZone(zone))

private fun dateFormat(locale: Locale) = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)

/** A stash shown on the graph: a row of its own just above [baseRow], its base commit's row. */
internal data class StashPlacement(val entry: com.srctool.graft.data.git.api.models.StashEntry, val baseRow: Int)

/** What a row of the table is. */
internal sealed interface TableEntry {
    data object Wip : TableEntry

    data class Stash(val placement: StashPlacement) : TableEntry

    data class Commit(val row: Int) : TableEntry
}

/**
 * Where everything sits in the table: the WIP row first, then the commits, with each stash just above its base
 * commit ([stashes] sorted by base row, then stash index). Stashes are few, so lookups walk them; commits are never
 * listed, so a 500k-row graph costs nothing here.
 */
internal class TableLayout(
    private val hasWip: Boolean,
    private val stashes: List<StashPlacement>,
    private val rows: Int,
) {
    private val wip = if (hasWip) 1 else 0

    val size: Int get() = wip + stashes.size + rows

    fun entry(index: Int): TableEntry {
        if (hasWip && index == 0) return TableEntry.Wip
        val t = index - wip
        stashes.forEachIndexed { j, p ->
            val at = p.baseRow + j
            if (t == at) return TableEntry.Stash(p)
            if (t < at) return TableEntry.Commit(t - j)
        }
        return TableEntry.Commit(t - stashes.size)
    }

    fun indexOf(selection: Selection): Int? = when (selection) {
        Selection.None -> null

        Selection.Wip -> if (hasWip) 0 else null

        is Selection.Row -> wip + selection.index + stashes.count { it.baseRow <= selection.index }

        is Selection.Stash ->
            stashes.indexOfFirst { it.entry.id == selection.id }.takeIf { it >= 0 }?.let { j ->
                wip + stashes[j].baseRow +
                    j
            }
    }

    fun selectionAt(index: Int): Selection = when (val e = entry(index)) {
        TableEntry.Wip -> Selection.Wip
        is TableEntry.Stash -> Selection.Stash(e.placement.entry.id)
        is TableEntry.Commit -> Selection.Row(e.row)
    }
}

/**
 * The line from the WIP row down to HEAD's row, [headRow]: drawn in [lane] through every row above it, in HEAD's
 * [color].
 */
internal data class WipLine(val headRow: Int, val lane: Int, val color: Int)

/**
 * Where the WIP row's line runs, or null when HEAD isn't among the first rows loaded. It stays on HEAD's own lane
 * (Commit Graph note) while nothing above uses that lane; otherwise it would read as part of another line, so it takes
 * a lane right of everything above HEAD. A stash on a row sits one lane right of that row ([stashBaseRows]).
 */
internal fun wipLine(graph: LaidOutGraph, rowCount: Int, head: ObjectId?, stashBaseRows: Set<Int>): WipLine? {
    if (head == null) return null
    val headRow = (0 until minOf(rowCount, HEAD_SEARCH_ROWS)).firstOrNull { graph[it].commit.id == head } ?: return null
    val lane = graph[headRow].lane
    val clear =
        (0 until headRow).all { r ->
            val row = graph[r]
            row.lane != lane &&
                graph.through(r, lane) < 0 &&
                row.edges().none { it.from == lane || it.to == lane } &&
                !(r in stashBaseRows && row.width == lane)
        }
    val free = { (0..headRow).maxOf { graph[it].width + if (it in stashBaseRows) 1 else 0 } }
    return WipLine(headRow, if (clear) lane else free(), graph[headRow].color)
}

private const val HEAD_SEARCH_ROWS = 200
