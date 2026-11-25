// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain.api.models

import com.srctool.graft.data.git.api.models.FileDiff
import com.srctool.graft.data.git.api.models.ObjectId

/** Which version of a file the Changes panel shows: what is staged (`HEAD` to index), or what is not (index to files). */
internal enum class DiffSide { STAGED, UNSTAGED }

/** What the diff beside the Changes panel shows for one file (Task 12 wireframe, "States"). */
internal sealed interface WorkingDiff {
    /** Lines to tick: [diff] as git made it, and what the selection needs to know about it. */
    data class Text(val diff: FileDiff, val info: FileInfo) : WorkingDiff

    data class Binary(val size: Long?) : WorkingDiff

    /** Over the inline limit (2 MB by default); [size] in bytes. The user can ask for it anyway. */
    data class TooLarge(val size: Long) : WorkingDiff

    data class Renamed(val from: String) : WorkingDiff

    data class ModeOnly(val from: String, val to: String) : WorkingDiff

    data class Submodule(val from: ObjectId?, val to: ObjectId?) : WorkingDiff

    /** The file has no changes on this side any more (staged or edited again since the list was read). */
    data object Nothing : WorkingDiff
}

/** What [com.srctool.graft.feature.workingcopy.domain.api.usecases.StageChangesUseCase.discard] needs to be undone. */
internal data class DiscardRecord(
    /** The Local History snapshot of the tree as it was before the discard; null if none could be found. */
    val snapshot: ObjectId?,
    /** Each discarded path, with a fingerprint of the file as the discard left it (null: no file). */
    val left: Map<String, String?>,
)

internal sealed interface UndoDiscardOutcome {
    data object Restored : UndoDiscardOutcome

    /** No snapshot is known for the discard, so there is nothing to restore from; Local History has what it kept. */
    data object Unavailable : UndoDiscardOutcome

    /** [path] was edited after the discard, so restoring it would overwrite newer work: nothing was restored. */
    data class ChangedSince(val path: String) : UndoDiscardOutcome
}
