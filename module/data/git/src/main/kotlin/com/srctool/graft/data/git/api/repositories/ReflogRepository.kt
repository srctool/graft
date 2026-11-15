// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.repositories

import com.srctool.graft.data.git.api.models.ReflogPage
import com.srctool.graft.data.git.api.models.Remote
import com.srctool.graft.data.git.api.models.StashEntry
import com.srctool.sinew.models.Result
import java.nio.file.Path

/** The reflogs behind the timeline (Reflog and Recovery). View only in G1; restoring arrives in G3. */
public interface ReflogRepository {
    /**
     * Up to [limit] entries of [ref]'s reflog after skipping the newest [skip], newest first (`GIT-R-RFLG-…`). A ref
     * without a reflog (expired, or never logged) has no entries; an unknown ref fails.
     */
    public suspend fun entries(
        repository: Path,
        ref: String = "HEAD",
        skip: Int = 0,
        limit: Int = 200,
    ): Result<ReflogPage>

    /**
     * Whether git keeps reflogs here (`core.logAllRefUpdates`, off by default in a bare repository). When it doesn't,
     * the timeline shows only Graft's own operation log (Reflog and Recovery, edge cases).
     */
    public suspend fun enabled(repository: Path): Result<Boolean>
}

/** The stash list, newest first (`GIT-R-STSH-…`). */
public interface StashRepository {
    public suspend fun list(repository: Path): Result<List<StashEntry>>
}

/** The repository's remotes, credentials in URLs masked (`GIT-R-RMTS-…`). */
public interface RemoteRepository {
    public suspend fun list(repository: Path): Result<List<Remote>>
}
