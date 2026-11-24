// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.models

/** What `git clean` is asked to remove. */
public enum class CleanScope {
    /** Untracked files that aren't ignored: `git clean -d`. */
    UNTRACKED,

    /** Untracked and ignored files: `-x`. */
    UNTRACKED_AND_IGNORED,

    /** Only ignored files: `-X`. */
    IGNORED_ONLY,
}

/**
 * One thing `git clean` would remove. [path] is relative to the repository root, with `/` separators, and a folder's
 * ends with `/`. [lostBytes] is the part no snapshot can bring back: ignored files, and files over the snapshot size
 * limit (a nested repository is counted whole).
 */
public data class CleanEntry(
    val path: String,
    val directory: Boolean,
    val bytes: Long,
    val lostBytes: Long,
    /**
     * A repository of its own, or a folder that holds one and would go with it (git lists a folder that holds nothing
     * else as one entry): clean skips it unless it is named and confirmed (`-ff`).
     */
    val nestedRepository: Boolean,
)

/** [entries] are what a clean would remove, largest first. Submodules are tracked, so they are never in it. */
public data class CleanPreview(val scope: CleanScope, val entries: List<CleanEntry>) {
    val totalBytes: Long get() = entries.sumOf { it.bytes }
    val lostBytes: Long get() = entries.sumOf { it.lostBytes }
}

/** What a clean did: [snapshot] is the way back, for what a snapshot holds (null when nothing was removed). */
public data class CleanResult(val removed: List<String>, val snapshot: Snapshot?, val lostBytes: Long)
