// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.gitlog

import androidx.lifecycle.viewModelScope
import com.srctool.graft.data.git.api.repositories.GitLogRepository
import com.srctool.sinew.viewmodel.EventActionViewModel
import kotlinx.coroutines.launch
import java.nio.file.Path

/** Follows the commands that ran in [folder]; the selection stays on the same command as new ones arrive. */
internal class GitLogViewModel(private val folder: Path, private val log: GitLogRepository) :
    EventActionViewModel<GitLogState, GitLogEvent, GitLogAction, GitLogEffect>(GitLogState()) {
    private var started = false

    override fun mapEventToAction(event: GitLogEvent): GitLogAction? = when (event) {
        GitLogEvent.Opened -> if (started) null else GitLogAction.Start.also { started = true }
        is GitLogEvent.Selected -> GitLogAction.Select(event.index)
        is GitLogEvent.Moved -> GitLogAction.Move(event.by)
        is GitLogEvent.FailedOnly -> GitLogAction.Filter(event.on)
        GitLogEvent.CopyCommand -> GitLogAction.Copy
    }

    override suspend fun onAction(action: GitLogAction) {
        when (action) {
            GitLogAction.Start -> viewModelScope.launch { log.entries(folder).collect { setEntries(it) } }.let {}

            is GitLogAction.Select -> setState { copy(selected = action.index.coerceIn(-1, shown.lastIndex)) }

            is GitLogAction.Move ->
                setState {
                    if (shown.isEmpty()) this else copy(selected = (selected + action.by).coerceIn(0, shown.lastIndex))
                }

            is GitLogAction.Filter -> setState { copy(failedOnly = action.on, selected = -1) }

            GitLogAction.Copy -> currentState.selectedEntry?.let {
                emitEffect(GitLogEffect.CopyToClipboard("git ${it.command}"))
            }
        }
    }

    private fun setEntries(entries: List<com.srctool.graft.data.git.api.models.GitLogEntry>) {
        setState {
            val chosen = selectedEntry
            val next = copy(entries = entries)
            // The log is a ring: older rows fall off the front, so the selection follows its command, not its index.
            next.copy(selected = chosen?.let { c -> next.shown.indexOf(c) } ?: -1)
        }
    }
}
