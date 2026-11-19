// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.repositories

import com.srctool.graft.data.git.api.models.FileList
import com.srctool.sinew.models.Result
import java.nio.file.Path

/** What's in a commit's tree, for Search Everywhere's files group. */
public interface TreeRepository {
    /**
     * Every file in HEAD's tree (`git ls-tree -r -z --name-only HEAD`), keeping at most [limit] paths. A repository
     * with no commits has none, and that's not a failure. Failures: `GIT-R-TREE-…`.
     */
    public suspend fun files(repository: Path, limit: Int = DEFAULT_LIMIT): Result<FileList>

    public companion object {
        /** About 20 MB of paths at the cap. */
        public const val DEFAULT_LIMIT: Int = 200_000
    }
}
