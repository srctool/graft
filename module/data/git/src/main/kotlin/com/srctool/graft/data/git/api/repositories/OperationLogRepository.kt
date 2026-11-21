// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.repositories

import com.srctool.graft.data.git.api.models.OperationEntry
import com.srctool.graft.data.git.api.models.UndoState
import com.srctool.sinew.models.Result
import java.nio.file.Path

/**
 * The operation log (Git Engine): every mutating command Graft ran in a repository, with the refs before and after,
 * and Undo and Redo for commit and amend. It lives in `$GIT_COMMON_DIR/graft/oplog.jsonl` (Storage Formats), so it
 * survives a restart and is shared by the worktrees of one repository. Error codes are `GIT-R-OLOG-…`; a refused Undo
 * or Redo is `GIT-R-UNDO-MOVD` and changes nothing.
 */
public interface OperationLogRepository {
    /** The log, newest first, at most [limit] entries. A line that can't be read is skipped. */
    public suspend fun entries(repository: Path, limit: Int = Int.MAX_VALUE): Result<List<OperationEntry>>

    /** What Undo and Redo would do now, from the log alone; [undo] still checks the branch before moving it. */
    public suspend fun undoState(repository: Path): Result<UndoState>

    /**
     * Moves the branch back to where it was before the last commit or amend that wasn't undone, and returns that
     * operation. The change stays staged, as after `git reset --soft`. Refused when the branch is not where the
     * operation left it (someone committed since), so no commit is ever lost.
     */
    public suspend fun undo(repository: Path): Result<OperationEntry>

    /** Undoes the last Undo, with the same refusal when the branch has moved since. */
    public suspend fun redo(repository: Path): Result<OperationEntry>
}
