// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.models

/** One point in a worktree's Local History: a commit of the whole working tree (Local History, "Storage"). */
public data class Snapshot(
    val id: ObjectId,
    /** The snapshot before this one in the chain; null for the first. */
    val parent: ObjectId?,
    /** When it was taken, in seconds since 1970. */
    val time: Long,
    /** What the user called it ("before refactor"), or null for an automatic one. */
    val label: String?,
    /** The tree it holds: two snapshots with the same tree hold the same files. */
    val tree: ObjectId,
    /** The commit message as written, kept so rewriting the chain changes nothing about it. */
    val message: String,
)

/** What taking a snapshot did. */
public data class SnapshotResult(
    val snapshot: Snapshot,
    /** False when the working tree equals the last snapshot's, so that one is returned and nothing is written. */
    val created: Boolean,
)

/** A file as one snapshot has it. [blob] is null when the snapshot no longer has the file (it was deleted). */
public data class FileVersion(val snapshot: Snapshot, val blob: ObjectId?)

/** A snapshot a search found, with the paths that matched in it. */
public data class SnapshotHit(val snapshot: Snapshot, val paths: List<String>)

/** What thinning the history did. */
public data class Thinning(val kept: Int, val dropped: Int, val removedHistories: Int)

/** Limits for the history (Local History, "Retention"); the Settings Reference has the user's values. */
public data class LocalHistoryLimits(
    /** Files over this size are left out of a snapshot. */
    val maxFileBytes: Long = 10L * 1024 * 1024,
    /** The most unique object data one history may keep; the oldest unlabelled snapshots go first. */
    val budgetBytes: Long = 1024L * 1024 * 1024,
)
