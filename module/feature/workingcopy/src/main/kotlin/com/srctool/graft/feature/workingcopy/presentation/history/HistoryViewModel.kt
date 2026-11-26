// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.presentation.history

import androidx.lifecycle.viewModelScope
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.feature.workingcopy.domain.api.models.HistoryDiff
import com.srctool.graft.feature.workingcopy.domain.api.models.RestoreOutcome
import com.srctool.graft.feature.workingcopy.domain.api.models.RestoreRecord
import com.srctool.graft.feature.workingcopy.domain.api.models.UndoRestoreOutcome
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingCopy
import com.srctool.graft.feature.workingcopy.domain.api.usecases.LocalHistoryUseCase
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.session.SnapshotPart
import com.srctool.graft.presentation.view.CenterView
import com.srctool.graft.presentation.view.RepositoryViewState
import com.srctool.sinew.models.Result
import com.srctool.sinew.viewmodel.EventActionViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Local History (S11, Task 14): the timeline or one file's versions, what a snapshot changed, how a file differs now,
 * search, deleted files, and the restores. The lists are read again each time the session finishes a refresh, since a
 * new snapshot moves a ref and an edit changes what a diff shows. Events are the actions: nothing needs translating.
 */
internal class HistoryViewModel(
    private val workingCopy: WorkingCopy,
    private val session: RepositorySession,
    private val shared: RepositoryViewState,
    private val history: LocalHistoryUseCase,
    private val texts: HistoryTexts = HistoryTexts.English(),
) : EventActionViewModel<HistoryState, HistoryEvent, HistoryEvent, HistoryEffect>(HistoryState()) {
    private var watching = false
    private var listJob: Job? = null
    private var filesJob: Job? = null
    private var diffJob: Job? = null
    private var searchJob: Job? = null
    private var toastJob: Job? = null
    private var stamp = 0L

    override fun mapEventToAction(event: HistoryEvent): HistoryEvent = event

    override suspend fun onAction(action: HistoryEvent) {
        when (action) {
            is HistoryEvent.Shown -> show(action.path)

            HistoryEvent.Retry -> retry()

            is HistoryEvent.ViewChosen -> choose(action.view)

            is HistoryEvent.QueryTyped -> setState { copy(query = action.text) }

            is HistoryEvent.KindChosen -> {
                setState { copy(kind = action.kind) }
                if (currentState.hits != null) search()
            }

            HistoryEvent.SearchRun -> search()

            HistoryEvent.SearchCleared -> clearSearch()

            is HistoryEvent.FilterTyped -> setState { copy(deletedFilter = action.text) }

            is HistoryEvent.SnapshotSelected -> select(action.id)

            is HistoryEvent.SnapshotMoved -> moveSnapshot(action.by)

            is HistoryEvent.FileSelected -> selectFile(action.path)

            is HistoryEvent.FileMoved -> moveFile(action.by)

            is HistoryEvent.HunkTicked -> setState {
                copy(ticks = if (action.hunk in ticks) ticks - action.hunk else ticks + action.hunk)
            }

            is HistoryEvent.HunkRestored -> restoreHunks(setOf(action.hunk))

            HistoryEvent.TickedRestored -> restoreHunks(currentState.ticks)

            HistoryEvent.FileRestored -> restoreFile()

            HistoryEvent.AllRestored -> restoreAll()

            HistoryEvent.UndoClicked -> undo()

            HistoryEvent.ToastDismissed -> setState { copy(toast = null) }

            HistoryEvent.SnapshotAsked -> setState { copy(dialog = HistoryDialog.SnapshotNow) }

            is HistoryEvent.SnapshotConfirmed -> snapshotNow(action.label)

            HistoryEvent.PurgeAsked -> currentState.path?.let { setState { copy(dialog = HistoryDialog.Purge(it)) } }

            HistoryEvent.PurgeConfirmed -> purge()

            HistoryEvent.DialogCancelled -> setState { copy(dialog = null) }

            HistoryEvent.ShowAnyway -> {
                setState { copy(anyway = true) }
                readDiff()
            }

            is HistoryEvent.OutputShown -> setState { copy(outputShown = action.shown) }

            HistoryEvent.AllFilesAsked -> {
                shared.center.value = CenterView.Contributed(CENTER, "", back = CenterView.Graph)
                show(null)
            }

            HistoryEvent.Closed -> close()
        }
    }

    // ---- reading -------------------------------------------------------------------------------------------------

    private fun show(path: String?) {
        if (watching && path == currentState.path) {
            read()
            return
        }
        listJob?.cancel()
        filesJob?.cancel()
        diffJob?.cancel()
        searchJob?.cancel()
        // A toast and a dialog asked for while the screen was coming up stay.
        val toast = currentState.toast
        val dialog = currentState.dialog
        setState { HistoryState(path = path, kind = kind, toast = toast, dialog = dialog) }
        read()
        if (!watching) {
            watching = true
            watch()
        }
    }

    /** A finished refresh: an edit, or a new snapshot. The first value is the state the screen opened with. */
    private fun watch() {
        viewModelScope.launch {
            session.snapshot
                .map { SnapshotPart.STATUS in it.refreshing || SnapshotPart.REFS in it.refreshing }
                .distinctUntilChanged()
                .drop(1)
                .filter { !it }
                .collect { read() }
        }
    }

    private fun retry() {
        setState { copy(error = null, phase = HistoryState.Phase.Loading) }
        read()
    }

    private fun read() {
        listJob?.cancel()
        listJob = viewModelScope.launch {
            val path = currentState.path
            val failure: Throwable? =
                if (path == null) {
                    when (val r = history.timeline(workingCopy)) {
                        is Result.Success -> null.also { setState { copy(snapshots = r.data) } }
                        is Result.Failure -> r.error
                    }
                } else {
                    when (val r = history.versions(workingCopy, path)) {
                        is Result.Success -> null.also { setState { copy(versions = r.data) } }
                        is Result.Failure -> r.error
                    }
                }
            if (failure != null) {
                setState { copy(phase = HistoryState.Phase.Failed, error = failure) }
                return@launch
            }
            setState { copy(phase = HistoryState.Phase.Ready, error = null) }
            if (currentState.view == HistoryView.DELETED) readDeleted() else keepSelection()
        }
    }

    /** The selection survives a re-read; when its snapshot is gone (or nothing is selected) the newest is taken. */
    private fun keepSelection() {
        val entries = currentState.entries
        val selected = currentState.selected
        if (entries.any { it.snapshot.id == selected }) {
            readDiff()
            return
        }
        val first = entries.firstOrNull()?.snapshot?.id
        if (first == null) {
            setState { copy(selected = null, files = null, file = null, body = null, ticks = emptySet()) }
        } else {
            select(first)
        }
    }

    private fun select(id: ObjectId) {
        if (currentState.view == HistoryView.DELETED) return
        if (currentState.selected == id && currentState.body != null) return
        filesJob?.cancel()
        val path = currentState.path
        setState {
            copy(
                selected = id,
                files = null,
                file = path,
                body = null,
                ticks = emptySet(),
                anyway = false,
            )
        }
        if (path != null) {
            readDiff()
            return
        }
        filesJob = viewModelScope.launch {
            when (val r = history.changes(workingCopy, id)) {
                is Result.Success -> {
                    val matches = currentState.hits?.firstOrNull { it.snapshot.id == id }?.paths.orEmpty()
                    val first = r.data.firstOrNull { it.path in matches } ?: r.data.firstOrNull()
                    setState { copy(files = r.data) }
                    first?.let { selectFile(it.path) }
                }

                is Result.Failure -> setState { copy(files = emptyList(), error = r.error) }
            }
        }
    }

    private fun selectFile(path: String) {
        val state = currentState
        if (state.view == HistoryView.DELETED) {
            val found = state.deleted.firstOrNull { it.path == path } ?: return
            setState {
                copy(selected = found.lastSeen.id, file = path, body = null, ticks = emptySet(), anyway = false)
            }
        } else {
            if (state.file == path && state.body != null) return
            setState { copy(file = path, body = null, ticks = emptySet(), anyway = false) }
        }
        readDiff()
    }

    private fun readDiff() {
        diffJob?.cancel()
        val snapshot = currentState.selected ?: return
        val file = currentState.file ?: return
        if (currentState.body == null) setState { copy(body = HistoryBody.Loading) }
        diffJob = viewModelScope.launch {
            when (val r = history.diff(workingCopy, snapshot, file, currentState.anyway)) {
                is Result.Success -> setState {
                    val before = (body as? HistoryBody.Ready)?.diff
                    copy(body = HistoryBody.Ready(r.data), ticks = if (before == r.data) ticks else emptySet())
                }

                is Result.Failure -> setState { copy(body = HistoryBody.Failed(r.error)) }
            }
        }
    }

    private fun moveSnapshot(by: Int) {
        val entries = currentState.entries
        if (entries.isEmpty()) return
        val at = entries.indexOfFirst { it.snapshot.id == currentState.selected }
        val next = if (at < 0) 0 else (at + by.coerceIn(-entries.size, entries.size)).coerceIn(0, entries.lastIndex)
        select(entries[next].snapshot.id)
    }

    private fun moveFile(by: Int) {
        val state = currentState
        val paths =
            if (state.view == HistoryView.DELETED) {
                state.shownDeleted.map { it.path }
            } else {
                state.files.orEmpty().map { it.path }
            }
        if (paths.isEmpty()) return
        val at = paths.indexOf(state.file)
        val next = if (at < 0) 0 else (at + by.coerceIn(-paths.size, paths.size)).coerceIn(0, paths.lastIndex)
        selectFile(paths[next])
    }

    private fun choose(view: HistoryView) {
        if (currentState.view == view) return
        filesJob?.cancel()
        diffJob?.cancel()
        setState {
            copy(view = view, selected = null, files = null, file = null, body = null, ticks = emptySet())
        }
        if (view == HistoryView.DELETED) readDeleted() else keepSelection()
    }

    private fun readDeleted() {
        filesJob?.cancel()
        filesJob = viewModelScope.launch {
            when (val r = history.deleted(workingCopy)) {
                is Result.Success -> {
                    setState { copy(deleted = r.data) }
                    val file = currentState.file
                    if (r.data.none { it.path == file }) {
                        setState { copy(selected = null, file = null, body = null) }
                        r.data.firstOrNull()?.let { selectFile(it.path) }
                    }
                }

                is Result.Failure -> setState { copy(error = r.error) }
            }
        }
    }

    private fun search() {
        val query = currentState.query.trim()
        if (query.isEmpty()) {
            clearSearch()
            return
        }
        searchJob?.cancel()
        setState { copy(searching = true, error = null) }
        searchJob = viewModelScope.launch {
            when (val r = history.search(workingCopy, query, currentState.kind)) {
                is Result.Success -> {
                    setState {
                        copy(hits = r.data, searching = false, selected = null, files = null, file = path, body = null)
                    }
                    keepSelection()
                }

                is Result.Failure -> setState { copy(searching = false, error = r.error) }
            }
        }
    }

    private fun clearSearch() {
        searchJob?.cancel()
        val wasSearching = currentState.hits != null
        setState { copy(query = "", hits = null, searching = false) }
        if (wasSearching) keepSelection()
    }

    private fun close() {
        val center = shared.center.value
        if (center is CenterView.Contributed && center.id == CENTER) shared.center.value = center.back
    }

    // ---- writes --------------------------------------------------------------------------------------------------

    private suspend fun restoreFile() {
        val state = currentState
        val snapshot = state.selectedSnapshot ?: return
        val file = state.file ?: return
        if (!state.canRestoreFile) return
        restore(texts.restoredFile(file.substringAfterLast('/'), texts.time(snapshot.time))) {
            history.restoreFile(workingCopy, snapshot.id, file)
        }
    }

    private suspend fun restoreHunks(hunks: Set<Int>) {
        val state = currentState
        val snapshot = state.selectedSnapshot ?: return
        val file = state.file ?: return
        if (hunks.isEmpty()) return
        restore(texts.restoredFile(file.substringAfterLast('/'), texts.time(snapshot.time))) {
            history.restoreHunks(workingCopy, snapshot.id, file, hunks)
        }
    }

    private suspend fun restoreAll() {
        val snapshot = currentState.selectedSnapshot ?: return
        restore(null, snapshot.time) { history.restoreAll(workingCopy, snapshot.id) }
    }

    /** Runs one restore at a time, then says what happened and offers Undo. [done] is null for a restore of all. */
    private suspend fun restore(done: String?, time: Long = 0, run: suspend () -> Result<RestoreOutcome>) {
        if (currentState.busy != null) return
        setState { copy(busy = texts.restoring(), error = null, toast = null) }
        val result = run()
        setState { copy(busy = null) }
        when (result) {
            is Result.Success -> when (val outcome = result.data) {
                is RestoreOutcome.Done -> {
                    val record = outcome.record
                    val removed = (record as? RestoreRecord.Everything)?.removed ?: 0
                    toast(done ?: texts.restoredAll(texts.time(time), removed), record)
                    afterWrite()
                }

                is RestoreOutcome.Unprotected -> toast(texts.unprotected(outcome.path), null)
            }

            is Result.Failure -> setState { copy(error = result.error) }
        }
    }

    private suspend fun undo() {
        val record = currentState.toast?.undo ?: return
        if (currentState.busy != null) return
        setState { copy(busy = texts.restoring(), toast = null, error = null) }
        val result = history.undo(workingCopy, record)
        setState { copy(busy = null) }
        when (result) {
            is Result.Success -> {
                when (val outcome = result.data) {
                    UndoRestoreOutcome.Restored -> toast(texts.undone(), null)
                    is UndoRestoreOutcome.ChangedSince -> toast(texts.changedSince(outcome.path), null)
                }
                afterWrite()
            }

            is Result.Failure -> setState { copy(error = result.error) }
        }
    }

    private suspend fun snapshotNow(label: String) {
        if (currentState.busy != null) return
        setState { copy(dialog = null, busy = texts.snapshotting(), error = null) }
        val result = history.snapshotNow(workingCopy, label)
        setState { copy(busy = null) }
        when (result) {
            is Result.Success -> {
                val taken = result.data
                val time = texts.time(taken.snapshot.time)
                toast(if (taken.created) texts.snapshotTaken() else texts.nothingChanged(time), null)
                afterWrite()
            }

            is Result.Failure -> setState { copy(error = result.error) }
        }
    }

    private suspend fun purge() {
        val path = (currentState.dialog as? HistoryDialog.Purge)?.path ?: return
        if (currentState.busy != null) return
        setState { copy(dialog = null, busy = texts.removing(), error = null) }
        val result = history.purge(workingCopy, path)
        setState { copy(busy = null) }
        when (result) {
            is Result.Success -> {
                toast(texts.purged(path.substringAfterLast('/'), result.data), null)
                setState { copy(selected = null, body = null) }
                afterWrite()
            }

            is Result.Failure -> setState { copy(error = result.error) }
        }
    }

    private fun afterWrite() {
        setState { copy(ticks = emptySet()) }
        session.refresh(setOf(SnapshotPart.STATUS))
        // The session's refresh may not change anything it publishes (a snapshot only moves a Graft ref).
        read()
    }

    private fun toast(text: String, undo: RestoreRecord?) {
        val mine = ++stamp
        setState { copy(toast = HistoryToast(text, undo, mine)) }
        toastJob?.cancel()
        toastJob = viewModelScope.launch {
            delay(TOAST_MS)
            if (currentState.toast?.stamp == mine) setState { copy(toast = null) }
        }
    }

    internal companion object {
        /** The id of the center view this contributes (`CenterView.Contributed`); its key is the file, or empty. */
        const val CENTER: String = "local-history"
        const val TOAST_MS: Long = 10_000
    }
}
