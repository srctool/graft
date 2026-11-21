// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.gitlog

import com.srctool.graft.data.git.api.models.GitLogEntry

/** The Git log tool window (Task 20 wireframe): every git command the repository ran, newest at the bottom. */
internal data class GitLogState(
    val entries: List<GitLogEntry> = emptyList(),
    val failedOnly: Boolean = false,
    /** The selected row's index in [shown]. */
    val selected: Int = -1,
) {
    val shown: List<GitLogEntry> get() = if (failedOnly) entries.filter { it.failed } else entries

    val selectedEntry: GitLogEntry? get() = shown.getOrNull(selected)
}

internal sealed interface GitLogEvent {
    data object Opened : GitLogEvent

    data class Selected(val index: Int) : GitLogEvent

    data class Moved(val by: Int) : GitLogEvent

    data class FailedOnly(val on: Boolean) : GitLogEvent

    data object CopyCommand : GitLogEvent
}

internal sealed interface GitLogAction {
    data object Start : GitLogAction

    data class Select(val index: Int) : GitLogAction

    data class Move(val by: Int) : GitLogAction

    data class Filter(val on: Boolean) : GitLogAction

    data object Copy : GitLogAction
}

internal sealed interface GitLogEffect {
    data class CopyToClipboard(val text: String) : GitLogEffect
}
