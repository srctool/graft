// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain.api.models

import com.srctool.graft.data.git.api.models.FileDiff
import com.srctool.graft.data.git.api.models.ObjectId

/** What Local History searches: part of a path in any case, or the exact text added or removed. */
internal enum class HistorySearch { PATHS, TEXT }

/** How a file differs now from a snapshot (Task 14 wireframe, "The screen"). */
internal sealed interface HistoryDiff {
    data class Text(val diff: FileDiff) : HistoryDiff

    data class Binary(val size: Long?) : HistoryDiff

    /** Over the inline limit; [size] in bytes. The user can ask for it anyway. */
    data class TooLarge(val size: Long) : HistoryDiff

    /** The file is now as the snapshot had it. */
    data object Same : HistoryDiff
}

/** What a restore needs to be undone. */
internal sealed interface RestoreRecord {
    val before: ObjectId

    /** One file or some of its hunks: each path with a fingerprint of how the restore left it (null: no file). */
    data class Files(override val before: ObjectId, val left: Map<String, String?>) : RestoreRecord

    /** Everything: [after] is the tree the restore left, and [removed] how many newer files it took away. */
    data class Everything(override val before: ObjectId, val after: ObjectId, val removed: Int) : RestoreRecord
}

internal sealed interface RestoreOutcome {
    data class Done(val record: RestoreRecord) : RestoreOutcome

    /** The "before restore" snapshot can't hold [path] as it is (ignored, or too large), so nothing was changed. */
    data class Unprotected(val path: String) : RestoreOutcome
}

internal sealed interface UndoRestoreOutcome {
    data object Restored : UndoRestoreOutcome

    /** Edited since the restore, so undoing would overwrite newer work; [path] is null for a restore of everything. */
    data class ChangedSince(val path: String?) : UndoRestoreOutcome
}
