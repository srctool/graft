// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.repositories

import com.srctool.graft.data.git.api.models.Ref
import com.srctool.sinew.models.Result
import java.nio.file.Path

/** References of a repository: branches, remote branches, tags, the stash and Graft's refs. */
public interface RefRepository {
    /** All of them in one git call, sorted by name. A failure is Graft's `GitException`, for example `GIT-R-REFS-NF`. */
    public suspend fun refs(repository: Path): Result<List<Ref>>
}
