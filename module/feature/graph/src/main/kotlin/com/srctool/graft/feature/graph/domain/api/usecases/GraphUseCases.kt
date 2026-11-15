// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.domain.api.usecases

import com.srctool.graft.data.git.api.models.AuthorType
import com.srctool.graft.data.git.api.models.CommitRow
import com.srctool.graft.data.git.api.models.LogQuery
import com.srctool.graft.feature.graph.domain.api.models.CompareSummary
import com.srctool.graft.feature.graph.domain.api.models.GraphFilter
import com.srctool.graft.feature.graph.domain.api.models.GraphSearch
import com.srctool.graft.feature.graph.domain.api.models.LaidOutGraph
import com.srctool.graft.feature.graph.domain.api.models.TimelineEntry
import com.srctool.sinew.models.Result
import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

/**
 * Search (Commit Graph, "Search"): the commits matching a query, found by git and highlighted in the full graph, which
 * stays laid out as it is.
 */
internal interface SearchGraphUseCase {
    /**
     * Starts git when collected and stops it when collection stops. Emits [GraphSearch.Found] in batches, then
     * [GraphSearch.Done], or [GraphSearch.Failed] instead of throwing. [filter] is the graph's, so only commits the
     * graph shows can match; its refs must exist ([GraphFilter.prunedTo]).
     */
    fun search(repository: Path, query: LogQuery, filter: GraphFilter = GraphFilter()): Flow<GraphSearch>
}

/** Matching that needs no git: the graph already holds what these read. */
internal object GraphMatch {
    private val hex = Regex("^[0-9a-fA-F]{4,40}$")

    /** Whether [text] can be a commit hash prefix: 4 to 40 hex digits. */
    fun isHashPrefix(text: String): Boolean = hex.matches(text.trim())

    /** The rows whose commit hash starts with [prefix], top first; empty when [prefix] can't be a hash. */
    fun hashPrefix(graph: LaidOutGraph, prefix: String): List<Int> {
        if (!isHashPrefix(prefix)) return emptyList()
        val wanted = prefix.trim().lowercase()
        return (0 until graph.size).filter { graph[it].commit.id.hex.startsWith(wanted) }
    }

    /** The author-type filter (Commit Graph, "Filter"): highlighted when the commit's type is one of [types]. */
    fun authorType(commit: CommitRow, types: Set<AuthorType>): Boolean = commit.authorType in types
}

/** Compare's header numbers (Compare Branches). */
internal interface CompareSummaryUseCase {
    /** How far [compare] is from [base], and where they forked (`GIT-R-ABHD-…`, `GIT-R-MBAS-…`). */
    suspend fun summary(repository: Path, base: String, compare: String): Result<CompareSummary>
}

/**
 * The reflog timeline (Reflog and Recovery): HEAD's reflog and every local branch's, merged newest first and read a
 * page at a time.
 */
internal interface ReflogTimelineUseCase {
    /** A timeline over [refs], or HEAD and every local branch when null. */
    suspend fun open(repository: Path, refs: List<String>? = null): Result<ReflogTimeline>
}

/** A timeline being read; one reader at a time. */
internal interface ReflogTimeline {
    /**
     * Whether git keeps reflogs here (`core.logAllRefUpdates`; off in a bare repository by default). When it doesn't,
     * the timeline holds only what was logged before, and the screen says why it's short.
     */
    val enabled: Boolean

    /** True once every entry has been returned. */
    val exhausted: Boolean

    /** Up to [count] more entries, newest first; fewer only at the end. */
    suspend fun next(count: Int): Result<List<TimelineEntry>>
}
