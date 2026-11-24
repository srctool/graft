// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.repositories

import com.srctool.graft.data.git.api.models.WorkingBlocks
import com.srctool.sinew.models.Result
import java.nio.file.Path

/** Which changes [ChangeBlocksRepository.read] looks at. */
public enum class BlockSource {
    /** The working tree against `HEAD`: every change, staged or not. What a changelist is made of. */
    WORKTREE,

    /** The index against `HEAD`: what is staged, for the switch from the staging area to changelists. */
    INDEX,
}

/** The changes of a working tree as hunks with stable anchors, for Changelists (Changelists, "Model"). */
public interface ChangeBlocksRepository {
    /**
     * The hunks of every modified file and the paths that can only move whole (`GIT-R-BLKS-…`). Hunk ids are
     * positions in each file's diff made with [contextLines], so a selection built from the result must be applied
     * with the same setting (`StagingRepository`, `CommittingRepository.commit`).
     *
     * Conflicted and ignored paths are left out. Before the first commit every path is a whole change.
     */
    public suspend fun read(
        repository: Path,
        source: BlockSource = BlockSource.WORKTREE,
        contextLines: Int = 3,
    ): Result<WorkingBlocks>
}
