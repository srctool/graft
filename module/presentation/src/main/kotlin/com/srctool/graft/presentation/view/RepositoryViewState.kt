// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.presentation.view

import com.srctool.graft.data.app.api.models.RepositoryUiState
import com.srctool.graft.data.app.api.repositories.RepositoryUiStateRepository
import com.srctool.graft.data.git.api.models.DiffTarget
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.sinew.models.Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path

/** What is selected in a repository tab: nothing, the uncommitted changes, or a commit. */
sealed interface ViewSelection {
    data object None : ViewSelection

    data object Wip : ViewSelection

    data class Commit(val id: ObjectId) : ViewSelection
}

/** What a repository tab's center shows: the graph, Compare, or one file's diff (with a breadcrumb back). */
sealed interface CenterView {
    data object Graph : CenterView

    /**
     * Compare (S7): [a] is the base, [b] what's compared with it; each a full ref name or a commit id, never text as
     * typed. [b] is null while it's being picked.
     */
    data class Compare(val a: String, val b: String?) : CenterView

    /** [path] as [target] changes it; the diff screen steps through the target's files and goes [back] when closed. */
    data class Diff(val target: DiffTarget, val path: String, val back: CenterView = Graph) : CenterView
}

/**
 * What the panes of one repository tab share (G1 plan, Task 14, question 1): the saved per-repository choices
 * (`state.json`, ADR-27), the selection, and the center's content. The left panel, the graph and the details each
 * read it and change it; nothing else talks between them. One per repository tab, kept by its host.
 *
 * [ui] is written only through [update], so the file and every pane agree; a failed write keeps the change for the
 * session and leaves the file as it was.
 */
class RepositoryViewState(private val commonDir: Path, private val store: RepositoryUiStateRepository) {
    private val uiState = MutableStateFlow(RepositoryUiState())
    private val writes = Mutex()
    private var loaded = false

    val ui: StateFlow<RepositoryUiState> = uiState.asStateFlow()

    val selection: MutableStateFlow<ViewSelection> = MutableStateFlow(ViewSelection.None)

    val center: MutableStateFlow<CenterView> = MutableStateFlow(CenterView.Graph)

    /** A second commit marked in the graph (Mod+click), to compare with the selected one. */
    val marked: MutableStateFlow<ObjectId?> = MutableStateFlow(null)

    /** Reads `state.json` once; later calls return what's already loaded. A missing or unreadable file is the defaults. */
    suspend fun load(): RepositoryUiState = writes.withLock {
        if (!loaded) {
            (store.read(commonDir) as? Result.Success)?.data?.value?.let { uiState.value = it }
            loaded = true
        }
        uiState.value
    }

    /** Applies [change] to the choices as they are now, publishes it, and saves it. */
    suspend fun update(change: (RepositoryUiState) -> RepositoryUiState): RepositoryUiState = writes.withLock {
        val next = change(uiState.value)
        uiState.value = next
        store.update(commonDir) { change(it) }
        next
    }
}
