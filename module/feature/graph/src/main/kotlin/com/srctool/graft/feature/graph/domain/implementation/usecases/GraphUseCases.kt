// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.domain.implementation.usecases

import com.srctool.graft.data.git.api.models.LogQuery
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.RefKind
import com.srctool.graft.data.git.api.models.ReflogEntry
import com.srctool.graft.data.git.api.repositories.CommitRepository
import com.srctool.graft.data.git.api.repositories.CompareRepository
import com.srctool.graft.data.git.api.repositories.RefRepository
import com.srctool.graft.data.git.api.repositories.ReflogRepository
import com.srctool.graft.feature.graph.domain.api.models.CompareSummary
import com.srctool.graft.feature.graph.domain.api.models.GraphFilter
import com.srctool.graft.feature.graph.domain.api.models.GraphSearch
import com.srctool.graft.feature.graph.domain.api.models.TimelineEntry
import com.srctool.graft.feature.graph.domain.api.usecases.CompareSummaryUseCase
import com.srctool.graft.feature.graph.domain.api.usecases.ReflogTimeline
import com.srctool.graft.feature.graph.domain.api.usecases.ReflogTimelineUseCase
import com.srctool.graft.feature.graph.domain.api.usecases.SearchGraphUseCase
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.map
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.nio.file.Path

internal class GitSearchGraphUseCase(
    private val commits: CommitRepository,
    private val background: CoroutineDispatcher = Dispatchers.Default,
) : SearchGraphUseCase {
    override fun search(repository: Path, query: LogQuery, filter: GraphFilter): Flow<GraphSearch> = flow {
        var batch = ArrayList<ObjectId>()
        var total = 0
        var failure: Throwable? = null
        var last = System.nanoTime()
        // Like the graph's batches: the first matches show at once, then at most every 50 ms.
        commits.search(repository, query, filter.scope).rows.catch { failure = it }.collect { row ->
            batch.add(row.id)
            total++
            val now = System.nanoTime()
            if (total == batch.size || now - last > 50_000_000) {
                emit(GraphSearch.Found(batch))
                batch = ArrayList()
                last = now
            }
        }
        if (batch.isNotEmpty()) emit(GraphSearch.Found(batch))
        emit(failure?.let(GraphSearch::Failed) ?: GraphSearch.Done(total))
    }.flowOn(background)
}

internal class GitCompareSummaryUseCase(private val compare: CompareRepository) : CompareSummaryUseCase {
    override suspend fun summary(repository: Path, base: String, compare: String): Result<CompareSummary> {
        val counts =
            when (val r = this.compare.aheadBehind(repository, base, compare)) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        return this.compare.mergeBase(repository, base, compare).map {
            CompareSummary(base, compare, ahead = counts.ahead, behind = counts.behind, mergeBase = it)
        }
    }
}

internal class GitReflogTimelineUseCase(private val reflogs: ReflogRepository, private val refs: RefRepository) :
    ReflogTimelineUseCase {
    override suspend fun open(repository: Path, refs: List<String>?): Result<ReflogTimeline> {
        val names =
            refs ?: when (val r = this.refs.refs(repository)) {
                is Result.Success -> listOf(HEAD) +
                    r.data.filter { it.kind == RefKind.LOCAL_BRANCH }.map { it.name }.sorted()

                is Result.Failure -> return r
            }
        return reflogs.enabled(repository).map { enabled -> MergedTimeline(reflogs, repository, names, enabled) }
    }

    private companion object {
        const val HEAD = "HEAD"
    }
}

/**
 * Merges each ref's reflog, newest first. Every ref is read a page at a time; a move logged in several reflogs (HEAD
 * and its branch log a commit alike) is found by its time, old and new id and message, and becomes one entry.
 */
private class MergedTimeline(
    private val reflogs: ReflogRepository,
    private val repository: Path,
    refs: List<String>,
    override val enabled: Boolean,
) : ReflogTimeline {
    private class Cursor(val ref: String) {
        val buffer = ArrayList<ReflogEntry>()
        var read = 0
        var more = true
    }

    private val cursors = refs.distinct().map(::Cursor)

    override val exhausted: Boolean get() = cursors.all { it.buffer.isEmpty() && !it.more }

    override suspend fun next(count: Int): Result<List<TimelineEntry>> {
        require(count > 0) { "count must be > 0" }
        val out = ArrayList<TimelineEntry>(count)
        while (out.size < count) {
            for (c in cursors) if (c.buffer.isEmpty()) fill(c)?.let { return it }
            val newest = cursors.filter { it.buffer.isNotEmpty() }.maxByOrNull { it.buffer.first().time } ?: break
            val entry = newest.buffer.removeAt(0)
            val names = arrayListOf(newest.ref)
            for (other in cursors) {
                if (other === newest) continue
                // Read on until this reflog is past the entry's time, so a twin with the same time is in the buffer.
                while (other.more && (other.buffer.isEmpty() || other.buffer.last().time >= entry.time)) {
                    fill(other)?.let { return it }
                }
                val twin = other.buffer.indexOfFirst { it.time == entry.time && it.sameMove(entry) }
                if (twin >= 0) {
                    other.buffer.removeAt(twin)
                    names.add(other.ref)
                }
            }
            out.add(entry.toTimeline(cursors.map { it.ref }.filter { it in names }))
        }
        return Result.Success(out)
    }

    /** Reads [c]'s next page; a failure is returned, and [c] is left as it was. */
    private suspend fun fill(c: Cursor): Result.Failure? {
        if (!c.more) return null
        return when (val r = reflogs.entries(repository, c.ref, skip = c.read, limit = PAGE)) {
            is Result.Success -> {
                c.buffer.addAll(r.data.entries)
                c.read += r.data.entries.size
                c.more = r.data.hasMore
                null
            }

            is Result.Failure -> r
        }
    }

    private fun ReflogEntry.sameMove(other: ReflogEntry) =
        newId == other.newId && oldId == other.oldId && message == other.message

    private fun ReflogEntry.toTimeline(refs: List<String>) =
        TimelineEntry(refs, time, offsetMinutes, oldId, newId, actor, message, subject)

    private companion object {
        const val PAGE = 100
    }
}
