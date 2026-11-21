// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.models

/**
 * The refs an operation can move, read just before and just after it (Git Engine, "Operation log"). A null means the
 * ref doesn't exist: no commits yet, or no stash.
 */
public data class RefState(
    /** The commit HEAD is at. For a checked-out branch this is the branch's tip. */
    val head: ObjectId?,
    /** The full name of the checked-out branch (`refs/heads/main`); null when HEAD is detached. */
    val branch: String?,
    /** The tip of `refs/stash`. */
    val stash: ObjectId?,
)

/** What an operation was, for the ones Undo knows about (Working Copy, "Undo"). */
public enum class OperationKind {
    COMMIT,
    AMEND,

    /** An Undo of a [COMMIT] or [AMEND]. */
    UNDO,

    /** A Redo of an undone [COMMIT] or [AMEND]. */
    REDO,

    /** Anything else that writes: stage, unstage, discard, and later operations. Recorded, not undone by Undo. */
    OTHER,
}

public enum class OperationOutcome { SUCCEEDED, FAILED, CANCELLED }

/** One mutating git command Graft ran, as the op log keeps it. */
public data class OperationEntry(
    /** Milliseconds since 1970, when the command started. */
    val time: Long,
    /** `main`, or `wt-<name>` for a linked worktree: the same ids as Local History's. */
    val worktree: String,
    val kind: OperationKind,
    /** `git` and the command's arguments, with secrets masked. The runner's fixed global options are not in it. */
    val argv: List<String>,
    /** Git's exit code; null when the command was cancelled or git could not start. */
    val exitCode: Int?,
    val outcome: OperationOutcome,
    /** The end of git's stderr when the command failed, with secrets masked; empty otherwise. */
    val stderrTail: String,
    val before: RefState,
    val after: RefState,
)

/** The operations Undo and Redo would act on now; null when there is none. */
public data class UndoState(val undo: OperationEntry?, val redo: OperationEntry?)
