// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.git

import androidx.compose.runtime.Immutable

/** One changed line of a [DiffDocument]: its hunk and its position in that hunk, both from 0. */
@Immutable
public data class DiffLineRef(val hunk: Int, val line: Int)

/** How much of a hunk is ticked: none of its changed lines, some of them, or all. */
public enum class DiffCheck { NONE, PARTIAL, ALL }

/**
 * What a [DiffViewer] needs to show hunk and line checkboxes: the lines ticked now ([selected]) and what to tell the
 * owner when the user toggles a hunk or a line. The viewer keeps no selection of its own and runs no git; the Changes
 * panel and Local History's restore hunk own it and decide what a tick means (stage, restore).
 *
 * With [lines] false only hunk headers can be ticked (Local History restores whole hunks). [hunkAction] adds a one-click
 * action to each hunk header.
 */
@Immutable
public class DiffSelection(
    public val selected: Set<DiffLineRef>,
    public val onToggleHunk: (hunk: Int) -> Unit,
    public val onToggleLine: (DiffLineRef) -> Unit,
    public val lines: Boolean = true,
    public val hunkAction: HunkAction? = null,
)

/** What a hunk header's own action says and does. */
@Immutable
public class HunkAction(public val label: String, public val run: (hunk: Int) -> Unit)

/** The changed (added or removed) lines of hunk [hunk]: the ones that can be ticked. Context lines can't. */
public fun DiffDocument.changedLines(hunk: Int): List<DiffLineRef> =
    hunks[hunk].lines.mapIndexedNotNull { index, line ->
        if (line.kind == DiffLineKind.CONTEXT) null else DiffLineRef(hunk, index)
    }

/** Every changed line of the document. */
public fun DiffDocument.allChangedLines(): Set<DiffLineRef> = hunks.indices.flatMapTo(LinkedHashSet()) {
    changedLines(it)
}

/** How much of hunk [hunk] [selected] ticks; a hunk without changed lines is [DiffCheck.NONE]. */
public fun DiffDocument.checkOf(hunk: Int, selected: Set<DiffLineRef>): DiffCheck {
    val lines = changedLines(hunk)
    val ticked = lines.count { it in selected }
    return when {
        ticked == 0 -> DiffCheck.NONE
        ticked == lines.size -> DiffCheck.ALL
        else -> DiffCheck.PARTIAL
    }
}

/** [selected] after toggling hunk [hunk]: all of it when it wasn't all ticked, none of it when it was. */
public fun DiffDocument.toggleHunk(hunk: Int, selected: Set<DiffLineRef>): Set<DiffLineRef> {
    val lines = changedLines(hunk)
    return if (checkOf(hunk, selected) == DiffCheck.ALL) selected - lines.toSet() else selected + lines
}

/** [selected] after toggling [line]. */
public fun toggleLine(line: DiffLineRef, selected: Set<DiffLineRef>): Set<DiffLineRef> =
    if (line in selected) selected - line else selected + line
