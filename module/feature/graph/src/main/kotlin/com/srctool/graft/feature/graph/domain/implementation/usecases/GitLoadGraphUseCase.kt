// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.domain.implementation.usecases

import com.srctool.graft.data.git.api.models.CommitRow
import com.srctool.graft.data.git.api.repositories.CommitRepository
import com.srctool.graft.feature.graph.domain.api.models.GraphFilter
import com.srctool.graft.feature.graph.domain.api.models.GraphLoad
import com.srctool.graft.feature.graph.domain.api.models.GraphRow
import com.srctool.graft.feature.graph.domain.api.models.LaidOutGraph
import com.srctool.graft.feature.graph.domain.api.usecases.GraphLayout
import com.srctool.graft.feature.graph.domain.api.usecases.LoadGraphUseCase
import com.srctool.graft.feature.graph.domain.api.usecases.LoadGraphUseCase.Companion.FIRST_BATCH_ROWS
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.take
import java.nio.file.Path

/**
 * [LoadGraphUseCase] over [CommitRepository.log], with a fresh layout from [newLayout] per load. Parsing and batching
 * run on [background]; layout runs after `flowOn`, in the collector's context.
 */
internal class GitLoadGraphUseCase(
    private val commits: CommitRepository,
    private val newLayout: () -> GraphLayout,
    private val background: CoroutineDispatcher = Dispatchers.Default,
) : LoadGraphUseCase {
    override fun load(repository: Path, filter: GraphFilter, limit: Int): Flow<GraphLoad> = flow {
        val graph = GrowingGraph(newLayout())
        var failed = false
        commits
            .log(repository, filter.scope)
            .rows
            .take(limit)
            .batched()
            .flowOn(background)
            .collect { chunk ->
                when (chunk) {
                    is Chunk.Rows -> {
                        chunk.rows.forEach(graph::add)
                        emit(GraphLoad.Progress(graph, graph.size, graph.widest))
                    }

                    is Chunk.Failure -> {
                        failed = true
                        emit(GraphLoad.Failed(graph, chunk.error))
                    }
                }
            }
        if (!failed) emit(GraphLoad.Loaded(graph, graph.size, graph.widest))
    }
}

/** The [LaidOutGraph] a load grows: rows appended in order, pass-through lines answered by the layout. */
private class GrowingGraph(private val layout: GraphLayout) : LaidOutGraph {
    private val rows = ArrayList<GraphRow>(1 shl 16)

    override val size: Int get() = rows.size
    override val widest: Int get() = layout.widest

    override fun get(index: Int): GraphRow = rows[index]

    override fun through(row: Int, lane: Int): Int = layout.through(row, lane)

    fun add(commit: CommitRow) {
        rows += layout.next(commit)
    }
}

/** What crosses from the background side: a batch of commits, or git's failure after the batches before it. */
private sealed interface Chunk {
    class Rows(val rows: List<CommitRow>) : Chunk

    class Failure(val error: Throwable) : Chunk
}

/**
 * Hands commits on in batches: the first as soon as it fills a screen, then at most every 50 ms, so a screen isn't
 * recomposed for every one of a million rows. A failure of the log (CommitRepository.log throws it to its collector)
 * comes last, as a [Chunk.Failure] after the partial batch: thrown through `flowOn`, it would cancel the channel and
 * drop the rows still in it. Cancellation isn't caught.
 */
private fun Flow<CommitRow>.batched(): Flow<Chunk> = flow {
    var batch = ArrayList<CommitRow>(FIRST_BATCH_ROWS)
    var sent = 0
    var last = System.nanoTime()
    var failure: Throwable? = null
    catch { failure = it }.collect { row ->
        batch += row
        val now = System.nanoTime()
        if ((sent == 0 && batch.size >= FIRST_BATCH_ROWS) || now - last > 50_000_000) {
            emit(Chunk.Rows(batch))
            sent += batch.size
            batch = ArrayList(maxOf(1024, batch.size * 2))
            last = now
        }
    }
    if (batch.isNotEmpty()) emit(Chunk.Rows(batch))
    failure?.let { emit(Chunk.Failure(it)) }
}
