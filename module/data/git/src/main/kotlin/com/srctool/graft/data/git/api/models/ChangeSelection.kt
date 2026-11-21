// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.models

/**
 * What the user picked in the Changes panel: one type for commit, discard, snapshot restore and, later, stash and
 * shelve (Working Copy and Commit, "Selection model").
 */
public data class ChangeSelection(val files: List<FileSelection>)

/**
 * One file in a [ChangeSelection]. [path] is the file's path in the repository, with `/` separators. [originalPath]
 * is the path before a rename; name it when the file is staged as a rename, so a whole-file unstage restores both
 * sides and a partial one reads the rename as one diff.
 *
 * Hunk ids and line numbers refer to the file's diff as [com.srctool.graft.data.git.api.repositories.StagingRepository]
 * reads it, with the same context-line setting the viewer used.
 */
public data class FileSelection(val path: String, val scope: Scope, val originalPath: String? = null) {
    public sealed interface Scope {
        /** The file as a whole: content, mode and rename. The only scope a binary file, a mode change or a submodule has. */
        public data object WholeFile : Scope

        /** The hunks at these positions in the file's diff (0 is the first). */
        public data class Hunks(val ids: Set<Int>) : Scope

        /** Some lines of some hunks. Context lines inside a range are ignored; only changes are selected. */
        public data class Lines(val ranges: List<LineRange>) : Scope
    }
}

/** Lines [first] to [last], both included, of the hunk at position [hunk]; positions count the hunk's lines from 0. */
public data class LineRange(val hunk: Int, val first: Int, val last: Int = first)
