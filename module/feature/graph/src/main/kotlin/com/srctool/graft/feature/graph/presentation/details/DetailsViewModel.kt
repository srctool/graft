// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.details

import androidx.lifecycle.viewModelScope
import com.srctool.graft.data.git.api.models.CommitDetails
import com.srctool.graft.data.git.api.models.DiffTarget
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.repositories.CommitRepository
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.view.CenterView
import com.srctool.graft.presentation.view.RepositoryViewState
import com.srctool.graft.presentation.view.ViewSelection
import com.srctool.sinew.models.Result
import com.srctool.sinew.viewmodel.EventActionViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * Commit details (S5): follows the tab's [shared] selection and reads the selected commit's details. A file opens its
 * diff in the center through [shared]; a parent selects that commit. [clock] gives epoch milliseconds.
 */
internal class DetailsViewModel(
    private val repository: Path,
    private val session: RepositorySession,
    private val shared: RepositoryViewState,
    private val commits: CommitRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : EventActionViewModel<DetailsState, DetailsEvent, DetailsAction, DetailsEffect>(DetailsState()) {
    private var opened = false
    private var loadJob: Job? = null
    private var shown: ObjectId? = null

    override fun mapEventToAction(event: DetailsEvent): DetailsAction? = when (event) {
        DetailsEvent.Opened -> if (opened) null else DetailsAction.Start.also { opened = true }

        is DetailsEvent.TreeChosen -> DetailsAction.Local { it.copy(tree = event.tree) }

        is DetailsEvent.Focused -> DetailsAction.Local { it.copy(focus = event.key) }

        is DetailsEvent.Moved ->
            DetailsAction.Local { s ->
                if (s.rows.isEmpty()) {
                    s
                } else {
                    val next = (s.focusIndex.takeIf { it >= 0 } ?: if (event.by > 0) -1 else s.rows.size) + event.by
                    s.copy(focus = s.rows[next.coerceIn(0, s.rows.lastIndex)].key)
                }
            }

        DetailsEvent.Expand -> DetailsAction.Expand

        DetailsEvent.Collapse -> DetailsAction.Collapse

        is DetailsEvent.Activated -> DetailsAction.Activate(event.key)

        is DetailsEvent.ParentChosen -> DetailsAction.Parent(event.index)

        DetailsEvent.CopyHash -> DetailsAction.Copy

        DetailsEvent.CompareMarked -> DetailsAction.Compare

        DetailsEvent.Retry -> DetailsAction.Reload

        is DetailsEvent.OutputShown -> DetailsAction.Local { it.copy(outputShown = event.shown) }
    }

    override suspend fun onAction(action: DetailsAction) {
        when (action) {
            DetailsAction.Start -> start()

            is DetailsAction.Local -> {
                setState(action.change)
                rebuildRows()
            }

            is DetailsAction.Activate -> activate(action.key)

            DetailsAction.Expand -> folder(open = true)

            DetailsAction.Collapse -> folder(open = false)

            is DetailsAction.Parent ->
                currentState.details?.parents?.getOrNull(action.index)?.let {
                    shared.selection.value =
                        ViewSelection.Commit(it)
                }

            DetailsAction.Copy -> currentState.details?.let { emitEffect(DetailsEffect.CopyToClipboard(it.id.hex)) }

            DetailsAction.Reload -> shown?.let { load(it) }

            DetailsAction.Compare -> {
                val a = currentState.details?.id ?: return
                val b = currentState.marked ?: return
                if (a != b) shared.center.value = CenterView.Compare(a.hex, b.hex)
            }
        }
    }

    private fun start() {
        viewModelScope.launch { shared.marked.collect { id -> setState { copy(marked = id) } } }
        viewModelScope.launch {
            combine(shared.selection, session.snapshot) { selection, snapshot ->
                selection to snapshot
            }.collect { (selection, snapshot) ->
                when (selection) {
                    ViewSelection.None -> {
                        loadJob?.cancel()
                        shown = null
                        setState { DetailsState(tree = tree, marked = marked) }
                    }

                    ViewSelection.Wip -> {
                        loadJob?.cancel()
                        shown = null
                        setState {
                            DetailsState(
                                phase = DetailsState.Phase.Wip,
                                wipFiles =
                                snapshot.summary?.changedFiles ?: 0,
                                tree = tree,
                            )
                        }
                    }

                    is ViewSelection.Commit -> if (selection.id != shown) load(selection.id)
                }
            }
        }
    }

    private fun load(id: ObjectId) {
        shown = id
        loadJob?.cancel()
        setState {
            copy(
                phase = DetailsState.Phase.Loading,
                details = null,
                error = null,
                rows = emptyList(),
                now =
                clock() / 1000,
            )
        }
        loadJob =
            viewModelScope.launch {
                when (val r = commits.details(repository, id)) {
                    is Result.Success -> show(r.data)
                    is Result.Failure -> setState { copy(phase = DetailsState.Phase.Failed, error = r.error) }
                }
            }
    }

    private fun show(details: CommitDetails) {
        setState { copy(phase = DetailsState.Phase.Loaded, details = details, collapsed = emptySet(), focus = null) }
        rebuildRows()
    }

    private fun rebuildRows() {
        val details = currentState.details ?: return
        val rows = fileRows(details.files, currentState.tree, currentState.collapsed)
        setState { copy(rows = rows, focus = focus?.takeIf { key -> rows.any { it.key == key } }) }
    }

    private fun activate(key: String) {
        val row = currentState.rows.firstOrNull { it.key == key } ?: return
        setState { copy(focus = key) }
        when (row) {
            is FileRow.Folder -> toggle(key)

            is FileRow.File -> {
                val commit = currentState.details?.id ?: return
                shared.center.value = CenterView.Diff(DiffTarget.Commit(commit), row.file.path)
            }
        }
    }

    private fun toggle(key: String) {
        setState { copy(collapsed = if (key in collapsed) collapsed - key else collapsed + key) }
        rebuildRows()
    }

    private fun folder(open: Boolean) {
        val s = currentState
        val index = s.focusIndex
        val row = s.rows.getOrNull(index) ?: return
        when {
            row is FileRow.Folder && row.expanded != open -> toggle(row.key)

            open && row is FileRow.Folder ->
                s.rows.getOrNull(index + 1)?.takeIf {
                    it.depth > row.depth
                }?.let { child -> setState { copy(focus = child.key) } }

            !open ->
                (index - 1 downTo 0).firstOrNull {
                    s.rows[it].depth < row.depth
                }?.let { p -> setState { copy(focus = s.rows[p].key) } }
        }
    }
}
