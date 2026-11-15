// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.domain.api.models

import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.Person

/**
 * Where a timeline entry is known from. G1 has no operation log, so every entry comes from git's reflog; G2's op log
 * adds entries matched to Graft's own operations, and with them the note's Graft / outside-Graft split.
 */
internal enum class TimelineSource {
    GIT,
}

/**
 * One move of a ref (Reflog and Recovery, "Timeline"). A move recorded in several reflogs at once (a commit is in
 * HEAD's reflog and its branch's) is one entry listing every [refs], HEAD first.
 */
internal data class TimelineEntry(
    val refs: List<String>,
    /** Seconds since the epoch. */
    val time: Long,
    val offsetMinutes: Int,
    /** The ref's value before the move; null for the oldest entry git kept. */
    val oldId: ObjectId?,
    val newId: ObjectId,
    val actor: Person,
    /** Git's reflog message, such as `reset: moving to HEAD~3`. */
    val message: String,
    /** The subject of [newId]'s commit. */
    val subject: String,
    val source: TimelineSource = TimelineSource.GIT,
)
