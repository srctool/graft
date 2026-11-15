// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.domain.api.usecases

import com.srctool.graft.feature.graph.domain.api.models.GraphFilter
import com.srctool.graft.feature.graph.domain.api.models.GraphLoad
import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

/**
 * Loads a repository's commit graph as `git log` streams it: the commits, laid out lane by lane, handed on in batches
 * so the first screen shows before the history ends.
 */
internal interface LoadGraphUseCase {
    /**
     * Starts `git log` when collected and stops it when collection stops. Emits [GraphLoad.Progress] per batch, then
     * [GraphLoad.Loaded], or [GraphLoad.Failed] instead of throwing. At most [limit] commits. Rows are laid out on the
     * collector's thread (git's output is parsed in the background), so the collector may read the graph between
     * steps. [filter] hides refs or solos them (git walks only what's shown); its refs must exist, see
     * [GraphFilter.prunedTo].
     */
    fun load(repository: Path, filter: GraphFilter = GraphFilter(), limit: Int = Int.MAX_VALUE): Flow<GraphLoad>

    companion object {
        /** Commits in the first batch: about one screen, so it shows before the rest arrives. */
        const val FIRST_BATCH_ROWS: Int = 60
    }
}
