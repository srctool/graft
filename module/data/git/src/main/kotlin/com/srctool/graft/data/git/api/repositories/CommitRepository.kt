// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.repositories

import com.srctool.graft.data.git.api.models.CommitDetails
import com.srctool.graft.data.git.api.models.CommitLog
import com.srctool.graft.data.git.api.models.LogQuery
import com.srctool.graft.data.git.api.models.LogScope
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.sinew.models.Result
import java.nio.file.Path

/** Commits of a repository, for the graph and its details panel. */
public interface CommitRepository {
    /**
     * Every commit reachable from the repository's branches, remote branches and tags, or from the refs [scope] names;
     * the stash and Graft's own refs (shelves, snapshots) are left out, since the graph draws those as separate nodes.
     * A ref in [scope] that doesn't exist fails the log (`GIT-R-LOG-…`), so callers pass refs that exist now.
     *
     * Git starts when [CommitLog.rows] is collected and stops when collection stops. A failure is thrown to the
     * collector as Graft's `GitException` (for example `GIT-R-LOG-NF` outside a repository) or Sinew's
     * `ParseException`; cancellation is never a failure.
     */
    public fun log(repository: Path, scope: LogScope = LogScope.All()): CommitLog

    /**
     * The commits of [log] over [scope] that match [query] (Commit Graph, "Search"), streamed the same way and
     * failing the same way. The graph highlights them in the full history.
     */
    public fun search(repository: Path, query: LogQuery, scope: LogScope = LogScope.All()): CommitLog

    /**
     * Everything the details panel shows about commit [id]: metadata, full message, trailers, signature status, and
     * the files it changed against its first parent (`GIT-R-DETL-…`, `GIT-R-FILS-…`).
     */
    public suspend fun details(repository: Path, id: ObjectId): Result<CommitDetails>
}
