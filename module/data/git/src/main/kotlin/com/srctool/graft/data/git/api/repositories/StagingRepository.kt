// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.repositories

import com.srctool.graft.data.git.api.models.ChangeSelection
import com.srctool.sinew.models.Result
import java.nio.file.Path

/**
 * Moves changes between the working tree, the index and nothing (Working Copy and Commit, "Selection model"). Every
 * call is one write on the repository's queue per git command, and nothing here commits.
 *
 * Hunk and line selections are positions in the file's diff, so [contextLines] must be the setting the diff the user
 * looked at was made with. A selection that names a hunk or line the diff no longer has fails with a patch error
 * (`GIT-R-…-PAT`) and changes nothing.
 */
public interface StagingRepository {
    /**
     * Puts the selected changes in the index, from the working tree (`GIT-R-APLY-…`, `GIT-R-ADD-…`). A whole file
     * goes through `git add`, so a deletion, a new file, a mode change and line-ending conversion all behave as git
     * does. Hunks and lines of an untracked file stage that part of the new file.
     */
    public suspend fun stage(repository: Path, selection: ChangeSelection, contextLines: Int = 3): Result<Unit>

    /**
     * Takes the selected changes out of the index, back to what `HEAD` has (`GIT-R-APLY-…`, `GIT-R-RSET-…`). The
     * working tree is not touched. Works before the first commit.
     */
    public suspend fun unstage(repository: Path, selection: ChangeSelection, contextLines: Int = 3): Result<Unit>

    /**
     * Throws away the selected changes in the working tree, back to what the index has (`GIT-R-APLY-…`,
     * `GIT-R-REST-…`). An untracked file is deleted, and only as a whole. The index is not touched.
     *
     * Nothing is kept: the Local History snapshot that makes a discard undoable is taken by the caller until
     * Task 4 moves it here.
     */
    public suspend fun discard(repository: Path, selection: ChangeSelection, contextLines: Int = 3): Result<Unit>
}
