// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.presentation.history

import com.srctool.graft.data.git.api.models.ChangedFile
import com.srctool.graft.data.git.api.models.DeletedFile
import com.srctool.graft.data.git.api.models.FileStatus
import com.srctool.graft.data.git.api.models.FileVersion
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.Snapshot
import com.srctool.graft.data.git.api.models.SnapshotHit
import com.srctool.graft.feature.workingcopy.domain.api.models.HistoryDiff
import com.srctool.graft.feature.workingcopy.domain.api.models.HistorySearch
import com.srctool.graft.feature.workingcopy.domain.api.models.RestoreRecord

internal enum class HistoryView { TIMELINE, DELETED }

/** One row of the timeline. */
internal data class HistoryEntry(
    val snapshot: Snapshot,
    /** In one file's history: this snapshot no longer has the file. */
    val deleted: Boolean = false,
    /** While searching: the paths that matched. */
    val matches: List<String> = emptyList(),
)

internal sealed interface HistoryBody {
    data object Loading : HistoryBody

    data class Ready(val diff: HistoryDiff) : HistoryBody

    data class Failed(val error: Throwable) : HistoryBody
}

internal sealed interface HistoryDialog {
    data object SnapshotNow : HistoryDialog

    /** Remove [path] from every snapshot; can't be undone. */
    data class Purge(val path: String) : HistoryDialog
}

/** A line under the screen for 10 seconds; [undo] makes it offer Undo. */
internal data class HistoryToast(val text: String, val undo: RestoreRecord? = null, val stamp: Long = 0)

/** Local History (S11, Task 14 wireframes). */
internal data class HistoryState(
    val phase: Phase = Phase.Loading,
    /** One file's history when set, else the whole working copy's. */
    val path: String? = null,
    val view: HistoryView = HistoryView.TIMELINE,
    val snapshots: List<Snapshot> = emptyList(),
    val versions: List<FileVersion> = emptyList(),
    val query: String = "",
    val kind: HistorySearch = HistorySearch.PATHS,
    /** The search's answer; null while not searching. */
    val hits: List<SnapshotHit>? = null,
    val searching: Boolean = false,
    val deleted: List<DeletedFile> = emptyList(),
    val deletedFilter: String = "",
    val selected: ObjectId? = null,
    /** What the selected snapshot changed; null while it is read. */
    val files: List<ChangedFile>? = null,
    val file: String? = null,
    val body: HistoryBody? = null,
    val ticks: Set<Int> = emptySet(),
    val anyway: Boolean = false,
    val busy: String? = null,
    val error: Throwable? = null,
    val outputShown: Boolean = false,
    val dialog: HistoryDialog? = null,
    val toast: HistoryToast? = null,
) {
    enum class Phase { Loading, Ready, Failed }

    /** The timeline's rows: a search's hits, one file's versions, or every snapshot. */
    val entries: List<HistoryEntry>
        get() = when {
            hits != null -> hits.map { HistoryEntry(it.snapshot, matches = it.paths) }
            path != null -> versions.map { HistoryEntry(it.snapshot, deleted = it.blob == null) }
            else -> snapshots.map { HistoryEntry(it) }
        }

    val shownDeleted: List<DeletedFile>
        get() = deleted.filter { it.path.contains(deletedFilter, ignoreCase = true) }

    val selectedSnapshot: Snapshot?
        get() = selected?.let { id ->
            entries.firstOrNull { it.snapshot.id == id }?.snapshot
                ?: deleted.firstOrNull { it.lastSeen.id == id }?.lastSeen
        }

    /** The selected snapshot has the selected file, so there is something to put back. */
    val canRestoreFile: Boolean
        get() {
            val file = file ?: return false
            val diff = (body as? HistoryBody.Ready)?.diff ?: return false
            if (diff == HistoryDiff.Same) return false
            return when {
                view == HistoryView.DELETED -> true
                path != null -> versions.firstOrNull { it.snapshot.id == selected }?.blob != null
                else -> files?.firstOrNull { it.path == file }?.status != FileStatus.DELETED
            }
        }
}

internal sealed interface HistoryEvent {
    /** The screen is shown for [path], or for the whole working copy. */
    data class Shown(val path: String?) : HistoryEvent

    data object Retry : HistoryEvent

    data class ViewChosen(val view: HistoryView) : HistoryEvent

    data class QueryTyped(val text: String) : HistoryEvent

    data class KindChosen(val kind: HistorySearch) : HistoryEvent

    data object SearchRun : HistoryEvent

    data object SearchCleared : HistoryEvent

    data class FilterTyped(val text: String) : HistoryEvent

    data class SnapshotSelected(val id: ObjectId) : HistoryEvent

    data class SnapshotMoved(val by: Int) : HistoryEvent

    data class FileSelected(val path: String) : HistoryEvent

    data class FileMoved(val by: Int) : HistoryEvent

    data class HunkTicked(val hunk: Int) : HistoryEvent

    data class HunkRestored(val hunk: Int) : HistoryEvent

    data object TickedRestored : HistoryEvent

    data object FileRestored : HistoryEvent

    data object AllRestored : HistoryEvent

    data object UndoClicked : HistoryEvent

    data object ToastDismissed : HistoryEvent

    data object SnapshotAsked : HistoryEvent

    data class SnapshotConfirmed(val label: String) : HistoryEvent

    data object PurgeAsked : HistoryEvent

    data object PurgeConfirmed : HistoryEvent

    data object DialogCancelled : HistoryEvent

    data object ShowAnyway : HistoryEvent

    data class OutputShown(val shown: Boolean) : HistoryEvent

    /** From one file's history to the whole working copy's. */
    data object AllFilesAsked : HistoryEvent

    data object Closed : HistoryEvent
}

internal sealed interface HistoryEffect
