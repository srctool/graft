// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.models

/** What a diff compares (Commit Graph, Compare Branches). */
public sealed interface DiffTarget {
    /** A commit against its first parent; a root commit against nothing. */
    public data class Commit(val id: ObjectId) : DiffTarget

    /** The trees as they are: `git diff A B`. */
    public data class Between(val from: String, val to: String) : DiffTarget

    /** What [tip] introduces since it forked from [base]: `git diff A...B`, what a pull request shows. */
    public data class Since(val base: String, val tip: String) : DiffTarget
}

/** How a diff is computed. Defaults match the Settings Reference; callers pass the user's settings. */
public data class DiffOptions(
    val contextLines: Int = 3,
    val detectRenames: Boolean = true,
    val ignoreWhitespace: Boolean = false,
)

/** How a file changed. */
public enum class FileStatus { ADDED, COPIED, DELETED, MODIFIED, RENAMED, TYPE_CHANGED, UNMERGED, UNKNOWN }

/** One changed file, from `--raw --numstat -z`: exact paths, whatever bytes they hold. */
public data class ChangedFile(
    val status: FileStatus,
    /** The path after the change (the old path for a deletion). */
    val path: String,
    /** The path before a rename or copy. */
    val oldPath: String?,
    /** How similar a renamed or copied file is, in percent. */
    val similarity: Int?,
    /** Git's mode before and after: `100644`, `100755`, `120000` (link), `160000` (submodule), `000000` (none). */
    val oldMode: String,
    val newMode: String,
    /** Lines added and removed; null for a binary file. */
    val additions: Int?,
    val deletions: Int?,
    /**
     * The file's object before and after (null when it didn't exist). Reading contents by id works for any path,
     * including names that aren't valid UTF-8 and so can't be written as `revision:path`.
     */
    val oldObject: ObjectId? = null,
    val newObject: ObjectId? = null,
) {
    val isBinary: Boolean get() = additions == null
    val isSubmodule: Boolean get() = oldMode == SUBMODULE || newMode == SUBMODULE
    val modeChanged: Boolean get() = oldMode != newMode && oldMode != NONE && newMode != NONE

    private companion object {
        const val SUBMODULE = "160000"
        const val NONE = "000000"
    }
}

/** A diff, file by file. */
public data class Diff(val files: List<FileDiff>)

/** One file's changes: its [hunks], none for a binary file, a pure rename or a mode change. */
public data class FileDiff(val file: ChangedFile, val hunks: List<Hunk>)

/** One `@@ -a,b +c,d @@` block. [section] is the function or heading git found above it, if any. */
public data class Hunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val section: String,
    val lines: List<DiffLine>,
)

/** One line of a hunk, with its number on each side it exists on. */
public data class DiffLine(
    val kind: Kind,
    val oldNumber: Int?,
    val newNumber: Int?,
    val text: String,
    /** Git's `\ No newline at end of file` follows this line. */
    val noNewlineAtEnd: Boolean = false,
) {
    public enum class Kind { CONTEXT, ADDED, REMOVED }
}

/** A file at a revision, for the side-by-side view. */
public sealed interface FileContent {
    public data class Text(val text: String) : FileContent

    /** Not shown as text: it has NUL bytes in its first 8,000 (git's own test). */
    public data class Binary(val size: Long) : FileContent

    /** Over the inline size limit; not loaded. */
    public data class TooLarge(val size: Long) : FileContent

    /** The path isn't in that revision. */
    public data object Missing : FileContent
}
