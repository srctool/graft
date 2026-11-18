// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.operations

import androidx.lifecycle.viewModelScope
import com.srctool.graft.data.git.api.models.RefKind
import com.srctool.graft.feature.graph.domain.api.models.TimelineEntry
import com.srctool.graft.feature.graph.domain.api.usecases.ReflogTimeline
import com.srctool.graft.feature.graph.domain.api.usecases.ReflogTimelineUseCase
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.session.SnapshotPart
import com.srctool.graft.presentation.view.CenterView
import com.srctool.graft.presentation.view.RepositoryViewState
import com.srctool.graft.presentation.view.ViewSelection
import com.srctool.sinew.models.Result
import com.srctool.sinew.viewmodel.EventActionViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * The reflog timeline (S8): pages through [timelines]' merged reflogs, reopens them when the session's refs move, and
 * hands the selected move to the other panes: its commit to the details, its before and after to Compare. View only
 * in G1. [clock] gives epoch milliseconds.
 */
internal class OperationsViewModel(
    private val repository: Path,
    private val session: RepositorySession,
    private val shared: RepositoryViewState,
    private val timelines: ReflogTimelineUseCase,
    private val clock: () -> Long = System::currentTimeMillis,
) : EventActionViewModel<OperationsState, OperationsEvent, OperationsAction, OperationsEffect>(OperationsState()) {
    private var opened = false
    private var timeline: ReflogTimeline? = null
    private var loadJob: Job? = null
    private var refsKey: Any? = null

    override fun mapEventToAction(event: OperationsEvent): OperationsAction? = when (event) {
        OperationsEvent.Opened -> if (opened) null else OperationsAction.Start.also { opened = true }

        is OperationsEvent.FilterChosen -> OperationsAction.Filter(event.ref)

        OperationsEvent.NearEnd -> OperationsAction.More

        is OperationsEvent.Selected -> OperationsAction.Select { event.index }

        is OperationsEvent.Moved ->
            OperationsAction.Select { s ->
                if (s.entries.isEmpty()) {
                    null
                } else {
                    (
                        s.selected.takeIf {
                            it >= 0
                        } ?: -1
                        ).let { (it + event.by).coerceIn(0, s.entries.lastIndex) }
                }
            }

        OperationsEvent.CompareMove -> OperationsAction.Compare

        OperationsEvent.CopyHash -> OperationsAction.Copy

        is OperationsEvent.MenuShown -> OperationsAction.Local { it.copy(menuOpen = event.open) }

        OperationsEvent.Retry -> OperationsAction.Reload

        is OperationsEvent.OutputShown -> OperationsAction.Local { it.copy(outputShown = event.shown) }
    }

    override suspend fun onAction(action: OperationsAction) {
        when (action) {
            OperationsAction.Start -> start()

            is OperationsAction.Local -> setState(action.change)

            is OperationsAction.Filter -> {
                setState { copy(filter = action.ref) }
                reopen(keep = null)
            }

            OperationsAction.More -> more()

            is OperationsAction.Select -> action.pick(currentState)?.let(::select)

            OperationsAction.Compare -> {
                val entry = currentState.selectedEntry ?: return
                val old = entry.oldId ?: return
                shared.center.value = CenterView.Compare(old.hex, entry.newId.hex)
            }

            OperationsAction.Copy -> currentState.selectedEntry?.let {
                emitEffect(OperationsEffect.CopyToClipboard(it.newId.hex))
            }

            OperationsAction.Reload -> reopen(keep = currentState.selectedEntry)
        }
    }

    private fun start() {
        viewModelScope.launch {
            session.snapshot.collect { snap ->
                if (SnapshotPart.REFS !in snap.loaded) return@collect
                val branches = snap.refs.filter { it.kind == RefKind.LOCAL_BRANCH }.map { it.name }.sorted()
                setState { copy(refs = listOf("HEAD") + branches) }
                // A ref moved: its reflog has a new entry. The first page is read again, the selection kept.
                val key = snap.refs.map { it.name to it.target }
                if (key == refsKey) return@collect
                refsKey = key
                reopen(keep = currentState.selectedEntry)
            }
        }
    }

    private fun reopen(keep: TimelineEntry?) {
        loadJob?.cancel()
        timeline = null
        setState {
            copy(
                phase = if (entries.isEmpty()) OperationsState.Phase.Loading else phase,
                error = null,
                now =
                clock() / 1000,
            )
        }
        loadJob =
            viewModelScope.launch {
                val refs = currentState.filter?.let { listOf(it) }
                val opened =
                    when (val r = timelines.open(repository, refs)) {
                        is Result.Success -> r.data

                        is Result.Failure -> {
                            setState { copy(phase = OperationsState.Phase.Failed, error = r.error) }
                            return@launch
                        }
                    }
                timeline = opened
                when (val page = opened.next(PAGE)) {
                    is Result.Success -> {
                        val entries = page.data
                        val selected = keep?.let { k -> entries.indexOfFirst { same(it, k) } } ?: -1
                        setState {
                            copy(
                                entries = entries,
                                phase = OperationsState.Phase.Ready,
                                enabled = opened.enabled,
                                exhausted = opened.exhausted,
                                selected = selected,
                            )
                        }
                    }

                    is Result.Failure -> setState { copy(phase = OperationsState.Phase.Failed, error = page.error) }
                }
            }
    }

    private suspend fun more() {
        val open = timeline ?: return
        val s = currentState
        if (s.exhausted || s.loadingMore || s.phase != OperationsState.Phase.Ready) return
        setState { copy(loadingMore = true) }
        when (val page = open.next(PAGE)) {
            is Result.Success -> setState {
                copy(entries = entries + page.data, exhausted = open.exhausted, loadingMore = false)
            }

            is Result.Failure -> setState {
                copy(loadingMore = false, phase = OperationsState.Phase.Failed, error = page.error)
            }
        }
    }

    private fun select(index: Int) {
        val entry = currentState.entries.getOrNull(index) ?: return
        setState { copy(selected = index) }
        shared.selection.value = ViewSelection.Commit(entry.newId)
    }

    /** The same move, across a reload: the same refs moved to the same commit at the same time. */
    private fun same(a: TimelineEntry, b: TimelineEntry) = a.newId == b.newId && a.time == b.time && a.refs == b.refs

    internal companion object {
        const val PAGE: Int = 200
    }
}
