// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.repositories

import com.srctool.graft.data.git.api.models.CommitLog
import java.nio.file.Path

/** Commits of a repository, for the graph. */
public interface CommitRepository {
    /**
     * Every commit reachable from the repository's branches, remote branches and tags; the stash and Graft's own refs
     * (shelves, snapshots) are left out, since the graph draws those as separate nodes.
     *
     * Git starts when [CommitLog.rows] is collected and stops when collection stops. A failure is thrown to the
     * collector as Graft's `GitException` (for example `GIT-R-LOG-NF` outside a repository) or Sinew's
     * `ParseException`; cancellation is never a failure.
     */
    public fun log(repository: Path): CommitLog
}
