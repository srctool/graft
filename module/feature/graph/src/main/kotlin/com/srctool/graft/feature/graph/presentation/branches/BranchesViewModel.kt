// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.branches

import androidx.lifecycle.viewModelScope
import com.srctool.graft.data.git.api.models.StashEntry
import com.srctool.graft.data.git.api.repositories.StashRepository
import com.srctool.graft.presentation.session.RepoSnapshot
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.session.SnapshotPart
import com.srctool.graft.presentation.view.CenterView
import com.srctool.graft.presentation.view.RepositoryViewState
import com.srctool.graft.presentation.view.ViewSelection
import com.srctool.sinew.models.Result
import com.srctool.sinew.viewmodel.EventActionViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * The left panel's sections (Task 14 wireframe): Local, Remote, Tags and Stashes from the repository [session], with
 * the hide, solo and pin choices of the [shared] state, which the graph follows. Collapsed sections and folders and the
 * speed-search text are the panel's own.
 */
internal class BranchesViewModel(
    private val repository: Path,
    private val session: RepositorySession,
    private val shared: RepositoryViewState,
    private val stashes: StashRepository,
) : EventActionViewModel<BranchesState, BranchesEvent, BranchesAction, BranchesEffect>(BranchesState()) {
    private var opened = false
    private var collapsed: Set<String> = emptySet()
    private var stashList: List<StashEntry> = emptyList()
    private var snapshot: RepoSnapshot? = null
    private var stashesFor: Any? = null

    override fun mapEventToAction(event: BranchesEvent): BranchesAction? = when (event) {
        BranchesEvent.Opened -> if (opened) null else BranchesAction.Start.also { opened = true }

        is BranchesEvent.Focused -> BranchesAction.Local { it.copy(focus = event.key) }

        is BranchesEvent.Moved ->
            BranchesAction.Local { s ->
                if (s.rows.isEmpty()) {
                    s
                } else {
                    val next = (s.focusIndex.takeIf { it >= 0 } ?: if (event.by > 0) -1 else s.rows.size) + event.by
                    s.copy(focus = s.rows[next.coerceIn(0, s.rows.lastIndex)].key)
                }
            }

        BranchesEvent.Expand -> BranchesAction.Expand

        BranchesEvent.Collapse -> BranchesAction.Collapse

        is BranchesEvent.Activated -> BranchesAction.Activate(event.key)

        is BranchesEvent.HideToggled ->
            BranchesAction.Choose { ui ->
                // A hidden ref can't stay soloed: solo would show what hide removes.
                if (event.refName in ui.hiddenRefs) {
                    ui.copy(hiddenRefs = ui.hiddenRefs - event.refName)
                } else {
                    ui.copy(hiddenRefs = ui.hiddenRefs + event.refName, soloRefs = ui.soloRefs - event.refName)
                }
            }

        is BranchesEvent.SoloToggled ->
            BranchesAction.Choose { ui ->
                if (event.refName in ui.soloRefs) {
                    ui.copy(soloRefs = ui.soloRefs - event.refName)
                } else {
                    ui.copy(soloRefs = ui.soloRefs + event.refName, hiddenRefs = ui.hiddenRefs - event.refName)
                }
            }

        is BranchesEvent.PinToggled ->
            BranchesAction.Choose { ui ->
                val pinned = ui.pinnedRefs
                ui.copy(pinnedRefs = if (event.refName in pinned) pinned - event.refName else pinned + event.refName)
            }

        is BranchesEvent.Searched -> BranchesAction.Local { it.copy(query = event.query) }

        is BranchesEvent.MenuShown -> BranchesAction.Local { it.copy(menuFor = event.key) }

        is BranchesEvent.CopyName -> BranchesAction.Copy(event.name)

        BranchesEvent.Retry -> BranchesAction.Retry

        is BranchesEvent.CompareWith -> BranchesAction.Compare(event.base, event.compare)
    }

    override suspend fun onAction(action: BranchesAction) {
        when (action) {
            BranchesAction.Start -> start()

            is BranchesAction.Local -> {
                setState(action.change)
                rebuild()
            }

            is BranchesAction.Activate -> activate(action.key)

            BranchesAction.Expand -> expand()

            BranchesAction.Collapse -> collapse()

            is BranchesAction.Choose -> shared.update(action.change)

            is BranchesAction.Copy -> emitEffect(BranchesEffect.CopyToClipboard(action.text))

            BranchesAction.Retry -> session.refresh(setOf(SnapshotPart.REFS))

            is BranchesAction.Compare -> shared.center.value = CenterView.Compare(action.base, action.compare)
        }
    }

    private suspend fun start() {
        shared.load()
        viewModelScope.launch {
            combine(session.snapshot, shared.ui) { snap, _ -> snap }.collect { snap ->
                snapshot = snap
                // The stash list is read again whenever the refs move (refs/stash is one of them).
                val key = snap.refs.map { it.name to it.target }
                if (SnapshotPart.REFS in snap.loaded && key != stashesFor) {
                    stashesFor = key
                    (stashes.list(repository) as? Result.Success)?.data?.let { stashList = it }
                }
                rebuild()
            }
        }
    }

    private fun rebuild() {
        val snap = snapshot ?: return
        val failure = snap.failures[SnapshotPart.REFS]
        val phase =
            when {
                SnapshotPart.REFS in snap.loaded -> BranchesState.Phase.Ready
                failure != null -> BranchesState.Phase.Failed
                else -> BranchesState.Phase.Loading
            }
        val ui = shared.ui.value
        val rows =
            panelRows(
                PanelInput(
                    snap.refs,
                    stashList,
                    ui.hiddenRefs,
                    ui.soloRefs,
                    ui.pinnedRefs,
                    collapsed,
                    currentState.query,
                ),
            )
        setState {
            // Focus stays on its row; when that row went away (filtered, collapsed), it moves to the first one.
            val focus = focus?.takeIf { key -> rows.any { it.key == key } } ?: rows.firstOrNull()?.key
            copy(rows = rows, phase = phase, error = failure, focus = focus, defaultBranch = defaultBranch(snap.refs))
        }
    }

    private fun activate(key: String) {
        val row = currentState.rows.firstOrNull { it.key == key } ?: return
        setState { copy(focus = key) }
        when (row) {
            is PanelRow.Header, is PanelRow.Folder -> toggle(key)
            is PanelRow.RefItem -> shared.selection.value = ViewSelection.Commit(row.commit)
            is PanelRow.StashItem -> shared.selection.value = ViewSelection.Commit(row.entry.id)
            is PanelRow.Empty -> Unit
        }
    }

    private fun toggle(key: String) {
        collapsed = if (key in collapsed) collapsed - key else collapsed + key
        rebuild()
    }

    private fun expand() {
        val s = currentState
        val row = s.rows.getOrNull(s.focusIndex) ?: return
        val open =
            when (row) {
                is PanelRow.Header -> !row.collapsed
                is PanelRow.Folder -> row.expanded
                else -> return
            }
        if (!open) {
            toggle(row.key)
        } else {
            s.rows.getOrNull(s.focusIndex + 1)?.takeIf {
                it.depth > row.depth
            }?.let { child -> setState { copy(focus = child.key) } }
        }
    }

    private fun collapse() {
        val s = currentState
        val index = s.focusIndex
        val row = s.rows.getOrNull(index) ?: return
        val open =
            when (row) {
                is PanelRow.Header -> !row.collapsed
                is PanelRow.Folder -> row.expanded
                else -> false
            }
        if (open) {
            toggle(row.key)
        } else {
            parentOf(s.rows, index)?.let { parent -> setState { copy(focus = s.rows[parent].key) } }
        }
    }
}
