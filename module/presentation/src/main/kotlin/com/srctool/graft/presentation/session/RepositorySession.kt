// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.presentation.session

import com.srctool.graft.data.git.api.models.Head
import com.srctool.graft.data.git.api.models.Ref
import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.data.git.api.models.RepositorySummary
import kotlinx.coroutines.flow.StateFlow

/** The parts of a [RepoSnapshot] that refresh on their own (Architecture, "Refresh"). */
enum class SnapshotPart {
    /** Branches, tags, remote branches: the graph's decorations and the left panel. */
    REFS,

    /** HEAD, its upstream and whether there are changes. */
    STATUS,
}

/**
 * What every pane of a repository tab shows about it right now (Architecture, "Repository session"). Each part keeps
 * its last good value while it refreshes, and its last failure until a refresh succeeds.
 */
data class RepoSnapshot(
    val location: RepositoryLocation,
    val refs: List<Ref> = emptyList(),
    /** Null until the first status read, and always for a bare repository, which has no working tree. */
    val summary: RepositorySummary? = null,
    /** The parts read at least once. */
    val loaded: Set<SnapshotPart> = emptySet(),
    val refreshing: Set<SnapshotPart> = emptySet(),
    val failures: Map<SnapshotPart, Throwable> = emptyMap(),
    /** The file watcher couldn't watch this repository and polls it instead; the status bar says so. */
    val polling: Boolean = false,
) {
    val head: Head? get() = summary?.head

    /** The full names of every ref, for pruning stale hide and solo choices. */
    val refNames: Set<String> get() = refs.mapTo(HashSet()) { it.name }
}

/** One open repository: its snapshot, kept current while a tab shows it. */
interface RepositorySession {
    val location: RepositoryLocation
    val snapshot: StateFlow<RepoSnapshot>

    /** Reads [parts] again now (after a change Graft made itself, or when the user asks). */
    fun refresh(parts: Set<SnapshotPart> = SnapshotPart.entries.toSet())

    /**
     * Runs [block], a git mutation, after the repository's other mutations (ADR-3: one write at a time per repository;
     * reads don't wait).
     */
    suspend fun <T> write(block: suspend () -> T): T
}

/** The sessions of every repository a tab shows, by location; a session opens with its first tab. */
interface RepositorySessions {
    val sessions: StateFlow<Map<RepositoryLocation, RepositorySession>>
}
