// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.graph

import com.srctool.graft.core.ui.git.RefLabel
import com.srctool.graft.core.ui.git.RefLabelKind
import com.srctool.graft.data.git.api.models.CommitRow
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.Ref
import com.srctool.graft.data.git.api.models.RefKind
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
