// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.repositories

import com.srctool.graft.data.git.api.models.GitLogEntry
import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

/** The commands the git runner ran (the Git log tool window): the last few hundred, oldest first, in memory only. */
public interface GitLogRepository {
    /** The commands that ran in [folder] or below it (a repository's working tree), as they happen. */
    public fun entries(folder: Path): Flow<List<GitLogEntry>>
}
