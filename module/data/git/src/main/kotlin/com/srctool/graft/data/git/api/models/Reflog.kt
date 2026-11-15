// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.models

/** One move of a ref, newest first (Reflog and Recovery, "Timeline"). */
public data class ReflogEntry(
    /** The ref whose log this is: `HEAD` or `refs/heads/main`. */
    val ref: String,
    /** Its position: `ref@{index}`, 0 for the newest. */
    val index: Int,
    /** What the ref pointed at before; null when the log goes no further back (the ref's creation, or expired). */
    val oldId: ObjectId?,
    val newId: ObjectId,
    /** Seconds since the epoch, and the time zone git recorded. */
    val time: Long,
    val offsetMinutes: Int,
    /** Who moved the ref (git's identity at the time). */
    val actor: Person,
    /** Git's reflog message: `reset: moving to HEAD~3`, `rebase (finish): …`, `commit: Fix login`. */
    val message: String,
    /** The subject of [newId]'s commit. */
    val subject: String,
)

/** A page of a reflog: [entries] newest first, and whether older ones remain. */
public data class ReflogPage(val entries: List<ReflogEntry>, val hasMore: Boolean)

/** One stash (`stash@{index}`), newest first. */
public data class StashEntry(
    val index: Int,
    val id: ObjectId,
    /** The commit the stash was made on (its first parent). */
    val base: ObjectId?,
    /** The branch it was made on, or null when HEAD was detached. */
    val branch: String?,
    /** `WIP on main: 4e27afc Fix login`, or the message given to `git stash -m`, as git shows it. */
    val message: String,
    val time: Long,
)

/**
 * A remote and where it fetches from and pushes to. Credentials written into a URL (`https://user:secret@host`) are
 * masked before they reach this model (Threat Model, ADR-7); an SSH user like `git@` isn't a secret and stays.
 */
public data class Remote(val name: String, val fetchUrl: String?, val pushUrl: String?)
