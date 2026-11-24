// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.app.api.repositories

import com.srctool.graft.data.app.api.models.ChangelistBook
import com.srctool.graft.data.app.api.models.Saved
import com.srctool.sinew.models.Result
import java.nio.file.Path

/**
 * A worktree's changelists in `<gitDir>/graft/changelists.json`; [gitDir] is the worktree's own `$GIT_DIR` (each
 * linked worktree has its own uncommitted changes, so its own lists).
 */
public interface ChangelistRepository {
    /** The stored book, or one with only the Default list when there is no file. Always has a Default and an active list. */
    public suspend fun read(gitDir: Path): Result<Saved<ChangelistBook>>

    /** Re-reads, applies [change] and writes; refused for a file saved by a newer Graft (`DATA-R-WRIT-VER`). */
    public suspend fun update(gitDir: Path, change: (ChangelistBook) -> ChangelistBook): Result<ChangelistBook>
}
