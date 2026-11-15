// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.repositories

import com.srctool.graft.data.git.api.models.AheadBehind
import com.srctool.graft.data.git.api.models.CommitLog
import com.srctool.graft.data.git.api.models.MergePreview
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.sinew.models.Result
import java.nio.file.Path

/**
 * "What's in B that isn't in A?" (Compare Branches). Revisions are anything git resolves: branches, tags, commits.
 * The file changes come from [DiffRepository] with `DiffTarget.Since` (what B introduces) or `DiffTarget.Between`.
 */
public interface CompareRepository {
    /** Commits reachable from [to] but not from [from] (`git log from..to`), streamed like the graph's log. */
    public fun commits(repository: Path, from: String, to: String): CommitLog

    /** How [compare] stands against [base]: "B is 5 ahead and 12 behind A" (`GIT-R-ABHD-…`). */
    public suspend fun aheadBehind(repository: Path, base: String, compare: String): Result<AheadBehind>

    /** Where [a] and [b] forked, or null when they share no history (`GIT-R-MBAS-…`). */
    public suspend fun mergeBase(repository: Path, a: String, b: String): Result<ObjectId?>

    /** Whether [from] would merge cleanly into [into], without touching anything (`GIT-R-MTRE-…`). */
    public suspend fun mergePreview(repository: Path, into: String, from: String): Result<MergePreview>
}
