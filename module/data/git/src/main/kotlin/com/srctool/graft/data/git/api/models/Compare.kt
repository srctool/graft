// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.models

/**
 * Filters for the graph's search (Commit Graph, "Search"). Every filter given must match. Message and author are
 * matched as plain text, not patterns; [path] is a literal path; [content] is a string git's pickaxe looks for
 * (`-S`), or a regular expression when [contentIsRegex] (`-G`).
 */
public data class LogQuery(
    val message: String? = null,
    val author: String? = null,
    val path: String? = null,
    val content: String? = null,
    val contentIsRegex: Boolean = false,
    val ignoreCase: Boolean = true,
    /** Only the first parent of each merge, as `git log --first-parent`. */
    val firstParent: Boolean = false,
)

/** How two branches stand: [ahead] commits only the compared branch has, [behind] commits only the base has. */
public data class AheadBehind(val ahead: Int, val behind: Int)

/**
 * Whether merging one branch into another would go cleanly (Compare Branches, `merge-tree --write-tree`), and if not,
 * which files would conflict. Nothing in the repository's refs, index or working tree changes.
 */
public data class MergePreview(val conflicts: List<String>) {
    val clean: Boolean get() = conflicts.isEmpty()
}
