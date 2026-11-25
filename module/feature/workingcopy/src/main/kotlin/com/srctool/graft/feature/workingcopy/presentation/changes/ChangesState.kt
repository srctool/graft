// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.presentation.changes

import com.srctool.graft.core.ui.git.DiffLineRef
import com.srctool.graft.data.git.api.models.ChangeSelection
import com.srctool.graft.feature.workingcopy.domain.api.models.ChangeRow
import com.srctool.graft.feature.workingcopy.domain.api.models.ChangesModel
import com.srctool.graft.feature.workingcopy.domain.api.models.DiffSide
import com.srctool.graft.feature.workingcopy.domain.api.models.DiscardRecord
import com.srctool.graft.feature.workingcopy.domain.api.models.SelectionState
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingDiff

/** The file whose diff is open in the center: [row] as one section lists it. */
internal data class OpenFile(val row: ChangeRow, val side: DiffSide)

internal sealed interface DiffBodyState {
    data object Loading : DiffBodyState

    data class Ready(val diff: WorkingDiff) : DiffBodyState

    data class Failed(val error: Throwable) : DiffBodyState
}

/** What a discard confirmation asks about; [summary] is the sentence, [paths] what goes. */
internal data class PendingDiscard(
    val selection: ChangeSelection,
    val paths: List<String>,
    /** Some of the paths are untracked files, which a discard deletes. */
    val deletes: Boolean,
    /** Lines of one file rather than whole files. */
    val lines: Int = 0,
)

/** A line under the panel for 10 seconds after something happened; [undo] makes it offer Undo. */
internal data class Toast(val text: String, val undo: DiscardRecord? = null, val stamp: Long = 0)

/** The Changes panel and the diff beside it (S9, Task 12 wireframes). */
internal data class ChangesState(
    val phase: Phase = Phase.Loading,
    val model: ChangesModel? = null,
    val lines: List<PanelLine> = emptyList(),
    val tree: Boolean = true,
    val collapsed: Set<String> = emptySet(),
    /** The branch's name, or "detached at abc1234". */
    val branch: String = "",
    /** The previous lists stay drawn, dimmed, while the next are read. */
    val refreshing: Boolean = false,
    val cursor: Int = -1,
    val open: OpenFile? = null,
    val body: DiffBodyState? = null,
    /** The lines ticked in [open]'s diff: the pending selection (question 1 of the wireframes). */
    val ticks: SelectionState = SelectionState.EMPTY,
    /** The ticks as the diff viewer wants them. */
    val ticked: Set<DiffLineRef> = emptySet(),
    /** What is running ("Staging 3 files…"), which disables the controls that would start another write. */
    val busy: String? = null,
    val error: Throwable? = null,
    val outputShown: Boolean = false,
    val discard: PendingDiscard? = null,
    val toast: Toast? = null,
    val anyway: Boolean = false,
) {
    enum class Phase { Loading, Ready, Failed }

    val isEmpty: Boolean get() = phase == Phase.Ready && model?.isEmpty != false
}

internal sealed interface ChangesEvent {
    data object Opened : ChangesEvent

    data object Retry : ChangesEvent

    data object TreeToggled : ChangesEvent

    data class Moved(val by: Int) : ChangesEvent

    data class CursorSet(val index: Int) : ChangesEvent

    /** Enter or a click: a file shows its diff, a folder opens or folds, a header does nothing. */
    data class Activated(val index: Int) : ChangesEvent

    /** Space or the checkbox: stage (Unstaged) or unstage (Staged) the line's files. */
    data class StageToggled(val index: Int) : ChangesEvent

    /** Stage all / Unstage all of the section at [index]. */
    data class SectionToggled(val index: Int) : ChangesEvent

    data class DiscardAsked(val index: Int) : ChangesEvent

    data class HunkTicked(val hunk: Int) : ChangesEvent

    data class LineTicked(val line: DiffLineRef) : ChangesEvent

    /** The action bar's main action: stage or unstage the ticked lines. */
    data object TicksApplied : ChangesEvent

    data object TicksDiscardAsked : ChangesEvent

    data object TicksCleared : ChangesEvent

    /** A hunk header's own Stage / Unstage: that hunk at once, no ticking. */
    data class HunkApplied(val hunk: Int) : ChangesEvent

    data object DiscardConfirmed : ChangesEvent

    data object DiscardCancelled : ChangesEvent

    data object UndoClicked : ChangesEvent

    data object ToastDismissed : ChangesEvent

    data object ShowAnyway : ChangesEvent

    /** Esc in the diff, or the breadcrumb: back to the graph. */
    data object DiffClosed : ChangesEvent

    data class OutputShown(val shown: Boolean) : ChangesEvent
}

internal sealed interface ChangesAction {
    data object Start : ChangesAction

    class Local(val change: (ChangesState) -> ChangesState) : ChangesAction

    data object Reload : ChangesAction

    data class Move(val by: Int) : ChangesAction

    data class Activate(val index: Int) : ChangesAction

    data class Toggle(val index: Int) : ChangesAction

    data class AskDiscard(val index: Int) : ChangesAction

    data class Tick(val hunk: Int?, val line: DiffLineRef?) : ChangesAction

    data object Apply : ChangesAction

    data object AskDiscardTicks : ChangesAction

    data class ApplyHunk(val hunk: Int) : ChangesAction

    data object ConfirmDiscard : ChangesAction

    data object Undo : ChangesAction

    data object Anyway : ChangesAction

    data object Close : ChangesAction

    data object Fold : ChangesAction
}

internal sealed interface ChangesEffect
