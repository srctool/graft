// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.models

import kotlin.math.abs

/**
 * What a hunk is, without where it sits now: hashes of its lines and its place in the *old* side (the file as `HEAD`
 * or the index has it). The old side only changes when `HEAD` moves, so edits to the working file above, inside or
 * around a hunk leave most of these alone. That is what lets a changelist find its hunks again (Changelists, "Model").
 */
public data class HunkAnchor(
    /** The first line of the hunk's old side, as the hunk header says. */
    val oldStart: Int,
    val oldCount: Int,
    /** The changed lines only: each removed line, then each added line, with its marker. */
    val change: String,
    /** The removed lines only; the hash of nothing for a pure insertion. */
    val removed: String,
    /** The hunk's whole old side, context included: what the hunk was made against. */
    val base: String,
    val removedCount: Int,
)

/** One hunk of a file's diff: [id] is its position there (0 is the first), as a [FileSelection.Scope.Hunks] names it. */
public data class WorkingHunk(val id: Int, val anchor: HunkAnchor, val additions: Int, val deletions: Int)

/** A modified file whose changes are text hunks, which a changelist can split between lists. */
public data class FileBlocks(val path: String, val originalPath: String?, val hunks: List<WorkingHunk>)

/**
 * A changed path that has no hunks to pick from (an untracked file, a deletion, a binary file, a mode change, a pure
 * rename, a submodule): it belongs to one list as a whole.
 */
public data class WholeChange(val path: String, val originalPath: String?, val untracked: Boolean)

/**
 * The working tree's changes in the shape changelists need: the hunks of modified files, and the paths that are only
 * whole. Read with [com.srctool.graft.data.git.api.repositories.ChangeBlocksRepository].
 */
public data class WorkingBlocks(val files: List<FileBlocks>, val whole: List<WholeChange>) {
    /**
     * The [ChangeSelection] of what [include] accepts: it is asked about each hunk (`path`, its id) and each whole
     * change (`path`, null). A file whose every hunk is accepted is selected as a whole, so its mode and rename go
     * along. The result commits, stages or shelves exactly that part.
     */
    public fun selection(include: (path: String, hunk: Int?) -> Boolean): ChangeSelection {
        val selected = ArrayList<FileSelection>()
        for (file in files) {
            val ids = file.hunks.filter { include(file.path, it.id) }.map { it.id }
            if (ids.isEmpty()) continue
            val scope =
                if (ids.size == file.hunks.size) {
                    FileSelection.Scope.WholeFile
                } else {
                    FileSelection.Scope.Hunks(ids.toSet())
                }
            selected += FileSelection(file.path, scope, file.originalPath)
        }
        for (change in whole) {
            if (include(change.path, null)) {
                selected += FileSelection(change.path, FileSelection.Scope.WholeFile, change.originalPath)
            }
        }
        return ChangeSelection(selected)
    }
}

/** A hunk a list owned before, as the list's file stored it: [tag] is the owner (a changelist id) and is opaque here. */
public data class StoredAnchor(val tag: String, val path: String, val anchor: HunkAnchor)

/**
 * Stored hunks matched against the hunks the working tree has now. Each current hunk is in exactly one of [kept],
 * [replaced] or [fresh]; each stored one is in [kept], [replaced] or [dropped].
 */
public data class Reanchoring(
    /** Found again: they stay with their owner. */
    val kept: List<Kept>,
    /** New changes with no owner yet (the active list takes them). */
    val fresh: List<Fresh>,
    /** A stored hunk that was edited past recognition next to where it was: its owner loses it (a notice). */
    val replaced: List<Replaced>,
    /** Stored hunks whose changes are gone (committed, reverted, or the file is no longer changed). */
    val dropped: List<StoredAnchor>,
) {
    public enum class How {
        /** Same lines at the same place. */
        EXACT,

        /** Same lines at another place: `HEAD` moved. */
        MOVED,

        /** Same old side, other new lines: the hunk was edited. */
        EDITED,

        /** Overlaps or touches where it was: a neighbouring change merged with it. */
        MERGED,
    }

    public data class Kept(val path: String, val hunk: WorkingHunk, val stored: StoredAnchor, val how: How)

    public data class Fresh(val path: String, val hunk: WorkingHunk)

    public data class Replaced(val path: String, val hunk: WorkingHunk, val stored: StoredAnchor)

    public companion object {
        /** How far (in old-side lines) a stored hunk and the one that replaced it can be and still be the same change. */
        private const val NEAR = 10

        /**
         * Matches [stored] to [current], file by file. A stored path may be the *old* path of a renamed file in
         * [current]: the assignment follows the rename.
         */
        public fun of(stored: List<StoredAnchor>, current: WorkingBlocks): Reanchoring {
            val kept = ArrayList<Kept>()
            val fresh = ArrayList<Fresh>()
            val replaced = ArrayList<Replaced>()
            // The same stored anchor value twice (two identical hunks) must still count twice.
            val byIndex = stored.withIndex().toList()
            val usedIndexes = HashSet<Int>()
            for (file in current.files) {
                val candidates = byIndex.filter { it.value.path == file.path || it.value.path == file.originalPath }
                val owner = arrayOfNulls<IndexedValue<StoredAnchor>>(file.hunks.size)
                val how = arrayOfNulls<How>(file.hunks.size)
                val claimed = candidates.map { it.index }.toMutableSet()
                for ((pass, rule) in RULES) {
                    for (candidate in candidates) {
                        if (candidate.index !in claimed) continue
                        val a = candidate.value.anchor
                        var best = -1
                        var distance = Int.MAX_VALUE
                        for (hunk in file.hunks) {
                            if (owner[hunk.id] != null || !rule(a, hunk.anchor)) continue
                            val d = abs(a.oldStart - hunk.anchor.oldStart)
                            if (d < distance) {
                                best = hunk.id
                                distance = d
                            }
                        }
                        if (best >= 0) {
                            owner[best] = candidate
                            how[best] = pass
                            claimed -= candidate.index
                        }
                    }
                }
                val unmatchedStored = candidates.filter { it.index in claimed }.toMutableList()
                for (hunk in file.hunks) {
                    val match = owner[hunk.id]
                    if (match != null) {
                        kept += Kept(file.path, hunk, match.value, how[hunk.id]!!)
                        usedIndexes += match.index
                        continue
                    }
                    val near =
                        unmatchedStored.minByOrNull { abs(it.value.anchor.oldStart - hunk.anchor.oldStart) }
                            ?.takeIf { abs(it.value.anchor.oldStart - hunk.anchor.oldStart) <= NEAR }
                    if (near != null) {
                        unmatchedStored -= near
                        usedIndexes += near.index
                        replaced += Replaced(file.path, hunk, near.value)
                    } else {
                        fresh += Fresh(file.path, hunk)
                    }
                }
            }
            // A stored hunk of a path that is now a whole change (or gone) has nothing to match: it is dropped.
            val dropped = byIndex.filter { it.index !in usedIndexes }.map { it.value }
            return Reanchoring(kept, fresh, replaced, dropped)
        }

        private val RULES: List<Pair<How, (HunkAnchor, HunkAnchor) -> Boolean>> =
            listOf(
                How.EXACT to { a, h -> a.change == h.change && a.oldStart == h.oldStart },
                How.MOVED to { a, h -> a.change == h.change },
                How.EDITED to { a, h -> a.base == h.base && a.oldStart == h.oldStart },
                // Edited and moved at once: same old side somewhere else, or the same removed lines.
                How.MOVED to { a, h -> a.base == h.base },
                How.EDITED to { a, h -> a.removedCount > 0 && a.removed == h.removed },
                How.MERGED to { a, h ->
                    a.oldStart <= h.oldStart + h.oldCount && h.oldStart <= a.oldStart + a.oldCount
                },
            )
    }
}
