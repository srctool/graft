// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain.api.models

import com.srctool.graft.data.git.api.models.ChangeSelection
import com.srctool.graft.data.git.api.models.FileSelection
import com.srctool.graft.data.git.api.models.LineRange

/** A hunk of a file's diff: [changeLines] are the positions (from 0, context counted) of its added and removed lines. */
internal data class HunkInfo(val id: Int, val changeLines: Set<Int>)

/**
 * What the selection needs to know about a file: its diff's hunks, none for a file that only moves whole (binary, mode
 * change, new empty file, submodule), and the path it was renamed from.
 */
internal data class FileInfo(
    val path: String,
    val hunks: List<HunkInfo> = emptyList(),
    val originalPath: String? = null,
)

internal enum class Check { NONE, PARTIAL, ALL }

/**
 * The ticks in the Changes panel and the diff: whole files, hunks, lines, or a mix, kept per path. It is one value, so
 * the panel and the diff viewer share it, and [toSelection] turns it into the [ChangeSelection] that commit, stage,
 * shelve and discard all take. Every call returns a new state; none changes this one.
 *
 * A file whose every hunk is ticked is ticked as a whole (so a mode change and a rename go with it), and a hunk whose
 * every changed line is ticked is ticked as a hunk, so the same selection always has one form.
 */
internal class SelectionState private constructor(private val chosen: Map<String, Choice>) {
    private data class Choice(val whole: Boolean, val hunks: Set<Int>, val lines: Map<Int, Set<Int>>)

    val isEmpty: Boolean get() = chosen.isEmpty()

    val paths: Set<String> get() = chosen.keys

    fun check(file: FileInfo): Check {
        val choice = chosen[file.path] ?: return Check.NONE
        return if (choice.whole) Check.ALL else Check.PARTIAL
    }

    fun checkHunk(file: FileInfo, hunk: Int): Check {
        val choice = chosen[file.path] ?: return Check.NONE
        return when {
            choice.whole || hunk in choice.hunks -> Check.ALL
            choice.lines[hunk].orEmpty().isNotEmpty() -> Check.PARTIAL
            else -> Check.NONE
        }
    }

    fun isLineSelected(file: FileInfo, hunk: Int, line: Int): Boolean {
        val choice = chosen[file.path] ?: return false
        return choice.whole || hunk in choice.hunks || line in choice.lines[hunk].orEmpty()
    }

    /** A ticked or partly ticked file is cleared; an unticked one is ticked as a whole. */
    fun toggleFile(file: FileInfo): SelectionState =
        if (check(file) == Check.ALL) without(file.path) else with(file, Choice(true, emptySet(), emptyMap()))

    /** A ticked hunk is cleared; an unticked or partly ticked one is ticked. A file with no hunks is left alone. */
    fun toggleHunk(file: FileInfo, hunk: Int): SelectionState {
        if (file.hunks.none { it.id == hunk }) return this
        val c = expanded(file)
        val next =
            if (hunk in c.hunks) {
                c.copy(hunks = c.hunks - hunk)
            } else {
                c.copy(hunks = c.hunks + hunk, lines = c.lines - hunk)
            }
        return with(file, next)
    }

    /** Ticks or clears one changed line. A context line (not in the hunk's [HunkInfo.changeLines]) is ignored. */
    fun toggleLine(file: FileInfo, hunk: Int, line: Int): SelectionState {
        val info = file.hunks.firstOrNull { it.id == hunk } ?: return this
        if (line !in info.changeLines) return this
        val c = expanded(file)
        val next =
            if (hunk in c.hunks) {
                c.copy(hunks = c.hunks - hunk, lines = c.lines + (hunk to (info.changeLines - line)))
            } else {
                val now = c.lines[hunk].orEmpty()
                c.copy(lines = c.lines + (hunk to if (line in now) now - line else now + line))
            }
        return with(file, next)
    }

    /** Ticks [file] as a whole whatever it was. */
    fun select(file: FileInfo): SelectionState = with(file, Choice(true, emptySet(), emptyMap()))

    fun clear(path: String): SelectionState = without(path)

    /** Drops what is no longer in the panel: a path that was committed, discarded or reverted. */
    fun retain(paths: Set<String>): SelectionState = SelectionState(chosen.filterKeys { it in paths })

    /**
     * The selection as the data layer takes it, by path. [files] says how each ticked file's diff is cut; a path
     * missing from it is taken as a whole file. A mix of whole hunks and some lines of others becomes line ranges,
     * with a whole hunk as a range over all of its changed lines.
     */
    fun toSelection(files: Map<String, FileInfo>): ChangeSelection {
        val out = ArrayList<FileSelection>()
        for (path in chosen.keys.sorted()) {
            val choice = chosen.getValue(path)
            val info = files[path] ?: FileInfo(path)
            val scope =
                when {
                    choice.whole -> FileSelection.Scope.WholeFile
                    choice.lines.isEmpty() -> FileSelection.Scope.Hunks(choice.hunks)
                    else -> FileSelection.Scope.Lines(ranges(info, choice))
                }
            out += FileSelection(path, scope, info.originalPath)
        }
        return ChangeSelection(out)
    }

    private fun ranges(file: FileInfo, choice: Choice): List<LineRange> {
        val out = ArrayList<LineRange>()
        for (hunk in file.hunks.sortedBy { it.id }) {
            val picked =
                when {
                    hunk.id in choice.hunks -> hunk.changeLines
                    else -> choice.lines[hunk.id].orEmpty()
                }.sorted()
            if (picked.isEmpty()) continue
            var first = picked.first()
            var last = first
            for (position in picked.drop(1)) {
                // Positions between two ticked lines that are context don't break a range; an unticked change does.
                val gap = (last + 1 until position).any { it in hunk.changeLines }
                if (gap) {
                    out += LineRange(hunk.id, first, last)
                    first = position
                }
                last = position
            }
            out += LineRange(hunk.id, first, last)
        }
        return out
    }

    private fun expanded(file: FileInfo): Choice {
        val c = chosen[file.path] ?: return Choice(false, emptySet(), emptyMap())
        return if (c.whole) Choice(false, file.hunks.map { it.id }.toSet(), emptyMap()) else c
    }

    private fun with(file: FileInfo, choice: Choice): SelectionState {
        val normal = normalize(file, choice) ?: return without(file.path)
        return SelectionState(chosen + (file.path to normal))
    }

    private fun without(path: String): SelectionState = if (path in chosen) SelectionState(chosen - path) else this

    private fun normalize(file: FileInfo, choice: Choice): Choice? {
        if (file.hunks.isEmpty()) return if (choice.whole) choice.copy(hunks = emptySet(), lines = emptyMap()) else null
        if (choice.whole) return Choice(true, emptySet(), emptyMap())
        val byId = file.hunks.associateBy { it.id }
        val hunks = choice.hunks.filterTo(LinkedHashSet()) { it in byId }
        val lines = LinkedHashMap<Int, Set<Int>>()
        for ((id, picked) in choice.lines) {
            val info = byId[id] ?: continue
            val real = picked.intersect(info.changeLines)
            when {
                real.isEmpty() || id in hunks -> Unit
                real == info.changeLines -> hunks += id
                else -> lines[id] = real
            }
        }
        if (hunks.size == file.hunks.size) return Choice(true, emptySet(), emptyMap())
        if (hunks.isEmpty() && lines.isEmpty()) return null
        return Choice(false, hunks, lines)
    }

    override fun equals(other: Any?): Boolean = other is SelectionState && other.chosen == chosen

    override fun hashCode(): Int = chosen.hashCode()

    override fun toString(): String = "SelectionState($chosen)"

    companion object {
        val EMPTY: SelectionState = SelectionState(emptyMap())
    }
}
