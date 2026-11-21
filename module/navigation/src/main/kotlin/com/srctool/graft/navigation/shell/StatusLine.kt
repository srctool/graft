// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.shell

import com.srctool.graft.data.git.api.models.Head
import com.srctool.graft.presentation.session.RepoSnapshot

/** What the status bar says about the repository in front: its branch, how far from its upstream, and what's busy. */
internal data class StatusLine(
    /** The branch, or the short commit when HEAD is detached; null on Home or before the first read. */
    val branch: String? = null,
    val detached: Boolean = false,
    val ahead: Int = 0,
    val behind: Int = 0,
    val upstreamGone: Boolean = false,
    /** Some part of the repository is being read again. */
    val reading: Boolean = false,
    /** The watcher couldn't watch it, so Graft polls. */
    val polling: Boolean = false,
    /** A read failed and hasn't succeeded since. */
    val failed: Boolean = false,
)

internal fun statusOf(snapshot: RepoSnapshot?): StatusLine {
    snapshot ?: return StatusLine()
    val head = snapshot.head
    val upstream = snapshot.summary?.upstream
    return StatusLine(
        branch =
        when (head) {
            is Head.Branch -> head.name
            is Head.Detached -> head.commit.hex.take(SHORT)
            null -> null
        },
        detached = head is Head.Detached,
        ahead = upstream?.ahead ?: 0,
        behind = upstream?.behind ?: 0,
        upstreamGone = upstream?.gone == true,
        reading = snapshot.refreshing.isNotEmpty() || snapshot.loaded.isEmpty(),
        polling = snapshot.polling,
        failed = snapshot.failures.isNotEmpty(),
    )
}

private const val SHORT = 7
