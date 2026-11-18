// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.compare

import androidx.lifecycle.viewModelScope
import com.srctool.graft.data.git.api.models.DiffTarget
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.RefKind
import com.srctool.graft.data.git.api.repositories.CommitRepository
import com.srctool.graft.data.git.api.repositories.CompareRepository
import com.srctool.graft.data.git.api.repositories.DiffRepository
import com.srctool.graft.feature.graph.domain.api.usecases.CompareSummaryUseCase
import com.srctool.graft.feature.graph.presentation.details.FileRow
import com.srctool.graft.feature.graph.presentation.details.fileRows
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.view.CenterView
import com.srctool.graft.presentation.view.RepositoryViewState
import com.srctool.graft.presentation.view.ViewSelection
import com.srctool.sinew.models.Result
import com.srctool.sinew.viewmodel.EventActionViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * Compare (S7): shows what the tab's [shared] center names. Revisions are resolved to commit ids first and only ids
 * reach the compare commands, so nothing typed is ever read by git as an option. Selecting a commit shows it in the
 * details panel; a file opens its diff, which leads back here. [clock] gives epoch milliseconds.
 */
internal class CompareViewModel(
    private val repository: Path,
    private val session: RepositorySession,
    private val shared: RepositoryViewState,
    private val compare: CompareRepository,
    private val commits: CommitRepository,
    private val diffs: DiffRepository,
    private val summaries: CompareSummaryUseCase,
    private val clock: () -> Long = System::currentTimeMillis,
) : EventActionViewModel<CompareState, CompareEvent, CompareAction, Nothing>(CompareState()) {
    private var opened = false
    private var loadJob: Job? = null
    private var queryJob: Job? = null
    private var ids: Pair<ObjectId, ObjectId>? = null
    private var shown: CenterView.Compare? = null

    override fun mapEventToAction(event: CompareEvent): CompareAction? = when (event) {
        CompareEvent.Opened -> if (opened) null else CompareAction.Start.also { opened = true }

        CompareEvent.Back -> CompareAction.Back

        CompareEvent.Swapped -> currentState.let { s ->
            if (s.a != null &&
                s.b != null
            ) {
                CompareAction.Show(s.b, s.a)
            } else {
                null
            }
        }

        is CompareEvent.PickerOpened -> CompareAction.Local { it.copy(picker = event.side, query = "", typed = null) }

        is CompareEvent.QueryTyped -> CompareAction.Query(event.query)

        is CompareEvent.Picked ->
            currentState.let { s ->
                if (event.side ==
                    Side.A
                ) {
                    CompareAction.Show(event.revision, s.b)
                } else {
                    CompareAction.Show(s.a, event.revision)
                }
            }

        is CompareEvent.KindChosen -> CompareAction.Kind(event.kind)

        is CompareEvent.TreeChosen -> CompareAction.Local { s -> s.copy(tree = event.tree).withRows() }

        is CompareEvent.Focused -> CompareAction.Local { it.copy(focus = event.key) }

        is CompareEvent.Moved ->
            CompareAction.Local { s ->
                val keys = s.rowKeys
                if (keys.isEmpty()) {
                    s
                } else {
                    val at = keys.indexOf(s.focus).takeIf { it >= 0 } ?: if (event.by > 0) -1 else keys.size
                    s.copy(focus = keys[(at + event.by).coerceIn(0, keys.lastIndex)])
                }
            }

        CompareEvent.Expand -> CompareAction.Fold(open = true)

        CompareEvent.Collapse -> CompareAction.Fold(open = false)

        is CompareEvent.Activated -> CompareAction.Activate(event.key)

        CompareEvent.Retry -> CompareAction.Reload

        is CompareEvent.OutputShown -> CompareAction.Local { it.copy(outputShown = event.shown) }
    }

    override suspend fun onAction(action: CompareAction) {
        when (action) {
            CompareAction.Start -> start()

            is CompareAction.Local -> setState(action.change)

            is CompareAction.Show -> {
                setState { copy(picker = null) }
                val a = action.a ?: return
                shared.center.value = CenterView.Compare(a, action.b)
            }

            is CompareAction.Query -> query(action.query)

            is CompareAction.Kind -> {
                setState { copy(kind = action.kind) }
                loadFiles()
            }

            is CompareAction.Activate -> activate(action.key)

            is CompareAction.Fold -> fold(action.open)

            CompareAction.Back -> shared.center.value = CenterView.Graph

            CompareAction.Reload -> shown?.let {
                shown = null
                show(it)
            }
        }
    }

    private fun start() {
        viewModelScope.launch {
            session.snapshot.collect { snap ->
                val refs =
                    snap.refs
                        .filter { it.symbolicTarget == null && it.kind in PICKABLE }
                        .sortedWith(compareBy({ PICKABLE.indexOf(it.kind) }, { it.shortName }))
                        .map { it.name }
                setState { copy(refs = refs) }
            }
        }
        viewModelScope.launch { shared.center.collect { if (it is CenterView.Compare) show(it) } }
    }

    private fun show(center: CenterView.Compare) {
        if (center == shown) return
        shown = center
        loadJob?.cancel()
        ids = null
        setState {
            CompareState(
                a = center.a,
                b = center.b,
                phase = if (center.b == null) CompareState.Phase.Picking else CompareState.Phase.Loading,
                kind = kind,
                tree = tree,
                refs = refs,
                now = clock() / 1000,
            )
        }
        val b = center.b ?: return
        loadJob =
            viewModelScope.launch {
                // Only ids reach git from here on (`rev-parse --verify --end-of-options`).
                val aId = resolve(center.a) ?: return@launch
                val bId = resolve(b) ?: return@launch
                if (aId == bId) {
                    setState { copy(phase = CompareState.Phase.Same) }
                    return@launch
                }
                ids = aId to bId
                val summary =
                    when (val r = summaries.summary(repository, aId.hex, bId.hex)) {
                        is Result.Success -> r.data

                        is Result.Failure -> {
                            setState { copy(phase = CompareState.Phase.Failed, error = r.error) }
                            return@launch
                        }
                    }
                // With no shared history, only the direct difference means anything.
                val kind = if (summary.mergeBase == null) CompareKind.DIRECT else currentState.kind
                setState { copy(phase = CompareState.Phase.Ready, summary = summary, kind = kind) }
                coroutineScope {
                    launch { lists(aId, bId) }
                    launch { loadFiles() }
                    if (summary.ahead > 0) launch { preview(aId, bId) }
                }
            }
    }

    private suspend fun resolve(revision: String): ObjectId? = when (val r = commits.resolve(repository, revision)) {
        is Result.Success ->
            r.data ?: run {
                setState { copy(phase = CompareState.Phase.NotFound, missing = revision) }
                null
            }

        is Result.Failure -> {
            setState { copy(phase = CompareState.Phase.Failed, error = r.error) }
            null
        }
    }

    private suspend fun lists(a: ObjectId, b: ObjectId) = coroutineScope {
        val inB = async { commitsOf(a, b) }
        val inA = async { commitsOf(b, a) }
        val onlyInB = inB.await()
        val onlyInA = inA.await()
        setState { copy(onlyInB = onlyInB, onlyInA = onlyInA) }
    }

    /** Commits reachable from [to] and not from [from], up to the list limit; a failure leaves the list empty. */
    private suspend fun commitsOf(from: ObjectId, to: ObjectId): List<CompareCommit> {
        val log = compare.commits(repository, from.hex, to.hex)
        val rows = log.rows.take(CompareState.LIST_LIMIT).catch { }.toList()
        return rows.map { CompareCommit(it, log.people[it.authorIndex].name) }
    }

    private suspend fun preview(a: ObjectId, b: ObjectId) {
        setState { copy(previewLoading = true) }
        val result = compare.mergePreview(repository, into = a.hex, from = b.hex)
        setState { copy(previewLoading = false, preview = (result as? Result.Success)?.data) }
    }

    private suspend fun loadFiles() {
        val (a, b) = ids ?: return
        val target =
            if (currentState.kind ==
                CompareKind.CHANGES
            ) {
                DiffTarget.Since(a.hex, b.hex)
            } else {
                DiffTarget.Between(a.hex, b.hex)
            }
        setState { copy(filesLoading = true) }
        when (val r = diffs.files(repository, target)) {
            is Result.Success -> setState { copy(files = r.data, filesLoading = false).withRows() }

            is Result.Failure -> setState {
                copy(filesLoading = false, phase = CompareState.Phase.Failed, error = r.error)
            }
        }
    }

    private fun query(text: String) {
        setState { copy(query = text, typed = null) }
        queryJob?.cancel()
        if (text.isBlank()) return
        queryJob =
            viewModelScope.launch {
                // Typing settles before git runs.
                delay(250)
                val id = (commits.resolve(repository, text.trim()) as? Result.Success)?.data
                setState { copy(typed = id) }
            }
    }

    private fun activate(key: String) {
        setState { copy(focus = key) }
        if (key.startsWith("commit:")) {
            shared.selection.value = ViewSelection.Commit(ObjectId(key.removePrefix("commit:")))
            return
        }
        when (val row = currentState.fileRows.firstOrNull { it.key == key }) {
            is FileRow.Folder -> setState {
                copy(
                    collapsed = if (key in
                        collapsed
                    ) {
                        collapsed - key
                    } else {
                        collapsed + key
                    },
                ).withRows()
            }

            is FileRow.File -> {
                val (a, b) = ids ?: return
                val target =
                    if (currentState.kind ==
                        CompareKind.CHANGES
                    ) {
                        DiffTarget.Since(a.hex, b.hex)
                    } else {
                        DiffTarget.Between(a.hex, b.hex)
                    }
                shared.center.value = CenterView.Diff(target, row.file.path, back = shown ?: CenterView.Graph)
            }

            null -> Unit
        }
    }

    private fun fold(open: Boolean) {
        val s = currentState
        val index = s.fileRows.indexOfFirst { it.key == s.focus }
        val row = s.fileRows.getOrNull(index) as? FileRow.Folder ?: return
        if (row.expanded !=
            open
        ) {
            setState { copy(collapsed = if (open) collapsed - row.key else collapsed + row.key).withRows() }
        }
    }

    private fun CompareState.withRows(): CompareState = copy(fileRows = fileRows(files, tree, collapsed))

    private companion object {
        val PICKABLE = listOf(RefKind.LOCAL_BRANCH, RefKind.REMOTE_BRANCH, RefKind.TAG)
    }
}
