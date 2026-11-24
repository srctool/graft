// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.repositories

import com.srctool.graft.data.git.api.models.CleanPreview
import com.srctool.graft.data.git.api.models.CleanResult
import com.srctool.graft.data.git.api.models.CleanScope
import com.srctool.sinew.models.Result
import java.nio.file.Path

/** `git clean`, always previewed first (Ignore and Clean, "Clean"). Codes `GIT-R-CLEN-…`. */
public interface CleanRepository {
    /** What a clean of [scope] would remove, with sizes, largest first. Nothing is changed. */
    public suspend fun preview(repository: Path, scope: CleanScope = CleanScope.UNTRACKED): Result<CleanPreview>

    /**
     * Removes [paths], which must all be in the preview taken now (`GitIgnoreRefusedException` otherwise, before
     * anything is removed). A snapshot is taken first and returned: it holds the untracked files, not ignored ones or
     * files over the size limit. A nested repository is removed only when its path is also in [confirmedNested].
     */
    public suspend fun clean(
        repository: Path,
        scope: CleanScope,
        paths: List<String>,
        confirmedNested: Set<String> = emptySet(),
    ): Result<CleanResult>
}
