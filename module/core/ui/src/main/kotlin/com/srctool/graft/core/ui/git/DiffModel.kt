// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.git

import androidx.compose.runtime.Immutable
import com.github.difflib.DiffUtils
import com.github.difflib.patch.DeltaType

/** A line's part in a diff. */
public enum class DiffLineKind { CONTEXT, ADDED, REMOVED }

/** One line of a [DiffHunkView], with its number on each side it exists on (the data layer's `DiffLine`). */
@Immutable
public data class DiffLineView(val kind: DiffLineKind, val oldNumber: Int?, val newNumber: Int?, val text: String)

/** One `@@ -a,b +c,d @@` block, as git printed its [header]. */
@Immutable
public data class DiffHunkView(val header: String, val lines: List<DiffLineView>)

/** One file's diff for [DiffViewer]; [path] picks the syntax highlighting. */
@Immutable
public data class DiffDocument(val path: String, val hunks: List<DiffHunkView>)

/** How [DiffViewer] lays a diff out (Settings Reference, Diff style). */
public enum class DiffMode { UNIFIED, SIDE_BY_SIDE }

/** A row of the diff as drawn: a hunk header, or a line (unified) or a pair of lines (side by side). */
internal sealed interface DiffRow {
    /** The change block this row is in, or -1 for unchanged rows and headers. */
    val change: Int

    data class Header(val text: String, val hunk: Int = -1) : DiffRow {
        override val change: Int get() = -1
    }

    /** A unified line. [words] are the ranges that changed within it, when it pairs with a line on the other side. */
    data class Unified(
        val line: DiffLineView,
        val words: List<IntRange>,
        override val change: Int,
        val ref: DiffLineRef? = null,
    ) : DiffRow

    /** A side-by-side pair; either side is null where the other side has a line the first doesn't. */
    data class Split(
        val left: DiffLineView?,
        val right: DiffLineView?,
        val leftWords: List<IntRange>,
        val rightWords: List<IntRange>,
        override val change: Int,
        val leftRef: DiffLineRef? = null,
        val rightRef: DiffLineRef? = null,
    ) : DiffRow {
        val refs: List<DiffLineRef> get() = listOfNotNull(leftRef, rightRef)
    }
}

/**
 * The rows [mode] draws for [document]. A run of removed lines followed by added lines is one change block; its lines
 * pair up in order (the first removed with the first added, …), and paired lines get word-level highlights. Side by
 * side, a pair shares a row and the longer side's extra lines face an empty cell.
 */
internal fun diffRows(document: DiffDocument, mode: DiffMode): List<DiffRow> {
    val rows = ArrayList<DiffRow>()
    var change = 0
    for ((hunkIndex, hunk) in document.hunks.withIndex()) {
        rows += DiffRow.Header(hunk.header, hunkIndex)
        val lines = hunk.lines
        var i = 0
        while (i < lines.size) {
            if (lines[i].kind == DiffLineKind.CONTEXT) {
                val line = lines[i++]
                rows += if (mode == DiffMode.UNIFIED) {
                    DiffRow.Unified(line, emptyList(), -1)
                } else {
                    DiffRow.Split(line, line, emptyList(), emptyList(), -1)
                }
                continue
            }
            val removed = ArrayList<DiffLineView>()
            val added = ArrayList<DiffLineView>()
            val removedAt = ArrayList<DiffLineRef>()
            val addedAt = ArrayList<DiffLineRef>()
            while (i < lines.size && lines[i].kind == DiffLineKind.REMOVED) {
                removedAt += DiffLineRef(hunkIndex, i)
                removed += lines[i++]
            }
            while (i < lines.size && lines[i].kind == DiffLineKind.ADDED) {
                addedAt += DiffLineRef(hunkIndex, i)
                added += lines[i++]
            }
            val words = (0 until minOf(removed.size, added.size)).map {
                WordDiff.diff(removed[it].text, added[it].text)
            }
            if (mode == DiffMode.UNIFIED) {
                removed.forEachIndexed { n, l ->
                    rows += DiffRow.Unified(l, words.getOrNull(n)?.first.orEmpty(), change, removedAt[n])
                }
                added.forEachIndexed { n, l ->
                    rows += DiffRow.Unified(l, words.getOrNull(n)?.second.orEmpty(), change, addedAt[n])
                }
            } else {
                for (n in 0 until maxOf(removed.size, added.size)) {
                    val w = words.getOrNull(n)
                    rows += DiffRow.Split(
                        removed.getOrNull(n),
                        added.getOrNull(n),
                        w?.first.orEmpty(),
                        w?.second.orEmpty(),
                        change,
                        removedAt.getOrNull(n),
                        addedAt.getOrNull(n),
                    )
                }
            }
            change++
        }
    }
    return rows
}

/** The row index where each change block starts, in order: where F7 and Shift+F7 go. */
internal fun changeStarts(rows: List<DiffRow>): List<Int> {
    val starts = ArrayList<Int>()
    var last = -1
    rows.forEachIndexed { index, row ->
        if (row.change >= 0 && row.change != last) {
            starts += index
            last = row.change
        }
    }
    return starts
}

/**
 * Word-level highlights between a removed line and the added line it pairs with (UI Design, "Diff viewer"), through
 * `java-diff-utils`. Lines are split into words, runs of spaces and single symbols, so a changed word lights up whole.
 * When most of a line changed, nothing is highlighted: the line's own color already says it.
 */
public object WordDiff {
    private val token = Regex("""\w+|\s+|[^\w\s]""")

    /** The changed ranges of [old] and of [new], or two empty lists when the lines are too different to be useful. */
    public fun diff(old: String, new: String): Pair<List<IntRange>, List<IntRange>> {
        if (old == new) return emptyList<IntRange>() to emptyList()
        val a = token.findAll(old).toList()
        val b = token.findAll(new).toList()
        val patch = DiffUtils.diff(a.map { it.value }, b.map { it.value })
        val oldRanges = ArrayList<IntRange>()
        val newRanges = ArrayList<IntRange>()
        for (delta in patch.deltas) {
            val source = delta.source
            val target = delta.target
            if (delta.type != DeltaType.INSERT && source.size() > 0) {
                oldRanges += a[source.position].range.first..a[source.position + source.size() - 1].range.last
            }
            if (delta.type != DeltaType.DELETE && target.size() > 0) {
                newRanges += b[target.position].range.first..b[target.position + target.size() - 1].range.last
            }
        }
        val changed = oldRanges.sumOf { it.count() } + newRanges.sumOf { it.count() }
        if (changed > (old.length + new.length) * MAX_CHANGED) return emptyList<IntRange>() to emptyList()
        return oldRanges to newRanges
    }

    /** Past this share of changed characters, the pair is a rewrite, not an edit. */
    private const val MAX_CHANGED = 0.6
}
