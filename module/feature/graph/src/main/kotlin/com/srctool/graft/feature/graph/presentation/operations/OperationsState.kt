// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.operations

import com.srctool.graft.feature.graph.domain.api.models.TimelineEntry

/** The reflog timeline (S8, Task 16 wireframe), in the Operations tool window. */
internal data class OperationsState(
    val entries: List<TimelineEntry> = emptyList(),
    val phase: Phase = Phase.Loading,
    /** Git keeps reflogs here (`core.logAllRefUpdates`); when not, a banner says why the timeline stops. */
    val enabled: Boolean = true,
    /** Every entry git kept is loaded. */
    val exhausted: Boolean = false,
    val loadingMore: Boolean = false,
    val error: Throwable? = null,
    val outputShown: Boolean = false,
    /** The ref the timeline shows: `HEAD`, a local branch's full name, or null for every one. */
    val filter: String? = null,
    /** What the filter offers: HEAD, then each local branch. */
    val refs: List<String> = emptyList(),
    /** The selected entry's index. */
    val selected: Int = -1,
    val menuOpen: Boolean = false,
    val now: Long = 0,
) {
    enum class Phase { Loading, Ready, Failed }

    val selectedEntry: TimelineEntry? get() = entries.getOrNull(selected)
}

internal sealed interface OperationsEvent {
    data object Opened : OperationsEvent

    data class FilterChosen(val ref: String?) : OperationsEvent

    /** The list scrolled near its end. */
    data object NearEnd : OperationsEvent

    data class Selected(val index: Int) : OperationsEvent

    data class Moved(val by: Int) : OperationsEvent

    /** Mod+D: Compare with the move's old commit as A and its new one as B. */
    data object CompareMove : OperationsEvent

    data object CopyHash : OperationsEvent

    data class MenuShown(val open: Boolean) : OperationsEvent

    data object Retry : OperationsEvent

    data class OutputShown(val shown: Boolean) : OperationsEvent
}

internal sealed interface OperationsAction {
    data object Start : OperationsAction

    class Local(val change: (OperationsState) -> OperationsState) : OperationsAction

    data class Filter(val ref: String?) : OperationsAction

    data object More : OperationsAction

    /** Selects the entry [pick] chooses from the state as it is when this runs. */
    class Select(val pick: (OperationsState) -> Int?) : OperationsAction

    data object Compare : OperationsAction

    data object Copy : OperationsAction

    data object Reload : OperationsAction
}

internal sealed interface OperationsEffect {
    data class CopyToClipboard(val text: String) : OperationsEffect
}

/**
 * Whether a move may have dropped commits from its ref (Task 16 wireframe): a reset, a rebase or a forced update moves
 * a ref to somewhere its old commits may not be reachable from. Git's reflog message names the command.
 */
internal fun mayDropCommits(message: String): Boolean {
    val command = message.substringBefore(':').trim()
    return command.startsWith("reset") || command.startsWith("rebase") || "forced-update" in message ||
        command.startsWith("pull --force")
}
