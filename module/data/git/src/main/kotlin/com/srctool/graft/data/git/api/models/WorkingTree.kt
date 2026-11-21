// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.models

/** How one side of a change (the index against HEAD, or the working tree against the index) differs. */
public enum class ChangeStatus {
    MODIFIED,
    TYPE_CHANGED,
    ADDED,
    DELETED,
    RENAMED,
    COPIED,
}

/** How a path in conflict changed on each side of a merge (git's two-letter unmerged codes). */
public enum class ConflictKind(public val code: String) {
    BOTH_MODIFIED("UU"),
    BOTH_ADDED("AA"),
    BOTH_DELETED("DD"),
    ADDED_BY_US("AU"),
    ADDED_BY_THEM("UA"),
    DELETED_BY_US("DU"),
    DELETED_BY_THEM("UD"),
    ;

    public companion object {
        public fun of(code: String): ConflictKind? = entries.firstOrNull { it.code == code }
    }
}

/** What changed inside a submodule: its checked-out commit, tracked files, or untracked files. */
public data class SubmoduleChange(val commitChanged: Boolean, val trackedModified: Boolean, val untracked: Boolean)

/** The file modes git reports for a path (`100644`, `100755`, `120000`, `160000`, `000000` when absent). */
public data class FileModes(val head: String, val index: String, val worktree: String) {
    /** The executable bit or type changed in the index against HEAD (not an add or a delete). */
    val stagedChange: Boolean get() = head != index && head != ABSENT && index != ABSENT

    /** The executable bit or type changed in the working tree against the index. */
    val unstagedChange: Boolean get() = index != worktree && index != ABSENT && worktree != ABSENT

    private companion object {
        const val ABSENT = "000000"
    }
}

/**
 * One path in `git status`. [index] is how the index differs from HEAD (null: not staged) and [worktree] how the
 * working tree differs from the index (null: no unstaged change). Untracked, ignored and conflicted paths have
 * neither: they have [untracked], [ignored] or [conflict]. Paths are relative to the repository root, with `/`
 * separators, as git prints them. Untracked and ignored files are listed one by one, never as their folder.
 */
public data class ChangeEntry(
    val path: String,
    /** The path a rename or copy came from. */
    val originalPath: String? = null,
    val index: ChangeStatus? = null,
    val worktree: ChangeStatus? = null,
    val untracked: Boolean = false,
    val ignored: Boolean = false,
    val conflict: ConflictKind? = null,
    val submodule: SubmoduleChange? = null,
    /** Null for untracked, ignored and conflicted paths. */
    val modes: FileModes? = null,
)

/** The state of a working tree: every changed path, in git's order, with the lists the Changes panel shows. */
public data class WorkingTree(val entries: List<ChangeEntry>) {
    /** Paths with a staged change; one that is also changed in the working tree is in [unstaged] as well. */
    val staged: List<ChangeEntry> get() = entries.filter { it.index != null }

    val unstaged: List<ChangeEntry> get() = entries.filter { it.worktree != null }

    val untracked: List<ChangeEntry> get() = entries.filter { it.untracked }

    val ignored: List<ChangeEntry> get() = entries.filter { it.ignored }

    val conflicted: List<ChangeEntry> get() = entries.filter { it.conflict != null }
}
