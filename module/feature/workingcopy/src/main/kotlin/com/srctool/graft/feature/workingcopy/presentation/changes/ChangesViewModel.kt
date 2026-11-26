// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.presentation.changes

import androidx.lifecycle.viewModelScope
import com.srctool.graft.core.ui.git.DiffLineRef
import com.srctool.graft.data.app.api.models.ChangesMode
import com.srctool.graft.data.git.api.models.ChangeSelection
import com.srctool.graft.data.git.api.models.FileSelection
import com.srctool.graft.data.git.api.models.Head
import com.srctool.graft.feature.workingcopy.domain.api.models.ChangeRow
import com.srctool.graft.feature.workingcopy.domain.api.models.ChangesModel
import com.srctool.graft.feature.workingcopy.domain.api.models.DiffSide
import com.srctool.graft.feature.workingcopy.domain.api.models.DiscardRecord
import com.srctool.graft.feature.workingcopy.domain.api.models.FileInfo
import com.srctool.graft.feature.workingcopy.domain.api.models.RowStatus
import com.srctool.graft.feature.workingcopy.domain.api.models.SectionKind
import com.srctool.graft.feature.workingcopy.domain.api.models.SelectionState
import com.srctool.graft.feature.workingcopy.domain.api.models.UndoDiscardOutcome
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingCopy
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingDiff
import com.srctool.graft.feature.workingcopy.domain.api.usecases.LoadChangesUseCase
import com.srctool.graft.feature.workingcopy.domain.api.usecases.PanelStateUseCase
import com.srctool.graft.feature.workingcopy.domain.api.usecases.StageChangesUseCase
import com.srctool.graft.feature.workingcopy.domain.api.usecases.WorkingDiffUseCase
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.session.SnapshotPart
import com.srctool.graft.presentation.view.CenterView
import com.srctool.graft.presentation.view.RepositoryViewState
import com.srctool.sinew.models.Result
import com.srctool.sinew.viewmodel.EventActionViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The Changes panel and the diff beside it (S9, Task 12): reads the lists each time the session's status refreshes,
 * stages and unstages by file, folder, hunk or ticked lines, and discards after a confirmation. The ticks are a
 * pending selection (question 1 of the wireframes): nothing changes in the index until an action applies them. The
 * sentences in toasts come from [texts], so the view model holds no strings of its own.
 */
internal class ChangesViewModel(
    private val workingCopy: WorkingCopy,
    private val session: RepositorySession,
    private val shared: RepositoryViewState,
    private val loads: LoadChangesUseCase,
    private val panel: PanelStateUseCase,
    private val diffs: WorkingDiffUseCase,
    private val staging: StageChangesUseCase,
    private val texts: ChangesTexts = ChangesTexts.English,
) : EventActionViewModel<ChangesState, ChangesEvent, ChangesAction, ChangesEffect>(ChangesState()) {
    private var started = false
    private var diffJob: Job? = null
    private var toastJob: Job? = null
    private var stamp = 0L

    override fun mapEventToAction(event: ChangesEvent): ChangesAction? = when (event) {
        ChangesEvent.Opened -> if (started) null else ChangesAction.Start.also { started = true }

        ChangesEvent.Retry -> ChangesAction.Reload

        ChangesEvent.TreeToggled -> ChangesAction.Fold

        is ChangesEvent.Moved -> ChangesAction.Move(event.by)

        is ChangesEvent.CursorSet ->
            ChangesAction.Local { it.copy(cursor = event.index.coerceIn(-1, it.lines.lastIndex)) }

        is ChangesEvent.Activated -> ChangesAction.Activate(event.index)

        is ChangesEvent.StageToggled -> ChangesAction.Toggle(event.index)

        is ChangesEvent.SectionToggled -> ChangesAction.Toggle(event.index)

        is ChangesEvent.DiscardAsked -> ChangesAction.AskDiscard(event.index)

        is ChangesEvent.HunkTicked -> ChangesAction.Tick(event.hunk, null)

        is ChangesEvent.LineTicked -> ChangesAction.Tick(null, event.line)

        ChangesEvent.TicksApplied -> ChangesAction.Apply

        ChangesEvent.TicksDiscardAsked -> ChangesAction.AskDiscardTicks

        ChangesEvent.TicksCleared -> ChangesAction.Local { retick(it, SelectionState.EMPTY) }

        is ChangesEvent.HunkApplied -> ChangesAction.ApplyHunk(event.hunk)

        ChangesEvent.DiscardConfirmed -> ChangesAction.ConfirmDiscard

        ChangesEvent.DiscardCancelled -> ChangesAction.Local { it.copy(discard = null) }

        ChangesEvent.UndoClicked -> ChangesAction.Undo

        ChangesEvent.ToastDismissed -> ChangesAction.Local { it.copy(toast = null) }

        ChangesEvent.ShowAnyway -> ChangesAction.Anyway

        ChangesEvent.DiffClosed -> ChangesAction.Close

        is ChangesEvent.OutputShown -> ChangesAction.Local { it.copy(outputShown = event.shown) }

        is ChangesEvent.StagedShown -> ChangesAction.ShowStaged(event.path)
    }

    override suspend fun onAction(action: ChangesAction) {
        when (action) {
            ChangesAction.Start -> start()

            is ChangesAction.Local -> setState(action.change)

            ChangesAction.Reload -> reload()

            ChangesAction.Fold -> fold()

            is ChangesAction.Move -> move(action.by)

            is ChangesAction.Activate -> activate(action.index)

            is ChangesAction.Toggle -> toggle(action.index)

            is ChangesAction.AskDiscard -> askDiscard(action.index)

            is ChangesAction.Tick -> tick(action)

            ChangesAction.Apply -> applyTicks()

            ChangesAction.AskDiscardTicks -> askDiscardTicks()

            is ChangesAction.ApplyHunk -> applyHunk(action.hunk)

            ChangesAction.ConfirmDiscard -> confirmDiscard()

            ChangesAction.Undo -> undo()

            ChangesAction.Anyway -> {
                setState { copy(anyway = true) }
                currentState.open?.let(::readDiff)
            }

            ChangesAction.Close -> close()

            is ChangesAction.ShowStaged -> showStaged(action.path)
        }
    }

    // ---- loading -------------------------------------------------------------------------------------------------

    private fun start() {
        viewModelScope.launch {
            (panel.read(workingCopy) as? Result.Success)?.data?.let { saved ->
                setState { copy(tree = saved.tree, collapsed = saved.collapsed) }
            }
            // Status finished refreshing: the lists are read again (the data layer folds a burst into one more read).
            val refreshes =
                session.snapshot
                    .map { SnapshotPart.STATUS in it.refreshing }
                    .distinctUntilChanged()
                    .filter { !it }
                    .map { }
            loads.observe(workingCopy, ChangesMode.STAGING_AREA, refreshes).collect { result ->
                when (result) {
                    is Result.Success -> apply(result.data.model)

                    is Result.Failure -> setState {
                        copy(phase = ChangesState.Phase.Failed, error = result.error, refreshing = false)
                    }
                }
            }
        }
        viewModelScope.launch {
            session.snapshot.map { it.head }.distinctUntilChanged().collect { head ->
                setState { copy(branch = branchName(head)) }
            }
        }
    }

    private fun reload() {
        setState {
            copy(
                refreshing = true,
                error = null,
                phase = if (model == null) ChangesState.Phase.Loading else phase,
            )
        }
        session.refresh(setOf(SnapshotPart.STATUS))
    }

    private fun branchName(head: Head?): String = when (head) {
        is Head.Branch -> head.name
        is Head.Detached -> texts.detached(head.commit.hex.take(SHORT))
        null -> ""
    }

    private fun apply(model: ChangesModel) {
        val previous = currentState
        val lines = panelLines(model, previous.tree, previous.collapsed)
        val keyOfCursor = previous.lines.getOrNull(previous.cursor)?.key
        val cursor =
            keyOfCursor?.let { k -> lines.indexOfFirst { it.key == k } }?.takeIf { it >= 0 }
                ?: previous.cursor.coerceIn(-1, lines.lastIndex)
        val open = previous.open?.let { o -> o.copy(row = rowOf(model, o) ?: o.row) }
        setState {
            copy(
                phase = ChangesState.Phase.Ready,
                model = model,
                lines = lines,
                cursor = cursor,
                refreshing = false,
                error = null,
                open = open,
            )
        }
        open?.let(::readDiff)
    }

    private fun rowOf(model: ChangesModel, open: OpenFile): ChangeRow? {
        val kind = if (open.side == DiffSide.STAGED) SectionKind.Staged else SectionKind.Unstaged
        return model.sections.firstOrNull { it.kind == kind }?.rows?.firstOrNull { it.path == open.row.path }
    }

    // ---- the diff ------------------------------------------------------------------------------------------------

    private fun readDiff(open: OpenFile) {
        diffJob?.cancel()
        if (currentState.body == null) setState { copy(body = DiffBodyState.Loading) }
        diffJob =
            viewModelScope.launch {
                when (val r = diffs.read(workingCopy, open.row, open.side, currentState.anyway)) {
                    is Result.Success -> {
                        val before = ((currentState.body as? DiffBodyState.Ready)?.diff as? WorkingDiff.Text)?.diff
                        val after = (r.data as? WorkingDiff.Text)?.diff
                        val same = before != null && before == after
                        setState {
                            retick(copy(body = DiffBodyState.Ready(r.data)), if (same) ticks else SelectionState.EMPTY)
                        }
                    }

                    is Result.Failure -> setState { copy(body = DiffBodyState.Failed(r.error)) }
                }
            }
    }

    /** [state] with [ticks] and the lines they tick, as the viewer wants them. */
    private fun retick(state: ChangesState, ticks: SelectionState): ChangesState {
        val info = infoOf(state)
        val ticked = LinkedHashSet<DiffLineRef>()
        if (info != null) {
            for (hunk in info.hunks) {
                for (line in hunk.changeLines.sorted()) {
                    if (ticks.isLineSelected(info, hunk.id, line)) ticked += DiffLineRef(hunk.id, line)
                }
            }
        }
        return state.copy(ticks = ticks, ticked = ticked)
    }

    private fun infoOf(state: ChangesState): FileInfo? =
        ((state.body as? DiffBodyState.Ready)?.diff as? WorkingDiff.Text)?.info

    private fun tick(action: ChangesAction.Tick) {
        val info = infoOf(currentState) ?: return
        val hunk = action.hunk
        val line = action.line
        val next =
            when {
                hunk != null -> currentState.ticks.toggleHunk(info, hunk)
                line != null -> currentState.ticks.toggleLine(info, line.hunk, line.line)
                else -> return
            }
        setState { retick(this, next) }
    }

    private fun close() {
        diffJob?.cancel()
        setState { copy(open = null, body = null, ticks = SelectionState.EMPTY, ticked = emptySet(), anyway = false) }
        val center = shared.center.value
        if (center is CenterView.Contributed && center.id == DIFF_CENTER) shared.center.value = center.back
    }

    // ---- the list ------------------------------------------------------------------------------------------------

    private fun move(by: Int) {
        val s = currentState
        if (s.lines.isEmpty()) return
        val next =
            if (s.cursor < 0) {
                if (by > 0) 0 else s.lines.lastIndex
            } else {
                (s.cursor + by).coerceIn(0, s.lines.lastIndex)
            }
        setState { copy(cursor = next) }
    }

    private fun fold() {
        val tree = !currentState.tree
        setState { copy(tree = tree, lines = model?.let { panelLines(it, tree, collapsed) } ?: lines) }
        viewModelScope.launch { panel.setTree(workingCopy, tree) }
    }

    private fun activate(index: Int) {
        val line = currentState.lines.getOrNull(index) ?: return
        setState { copy(cursor = index) }
        when (line) {
            is PanelLine.Header -> Unit

            is PanelLine.Folder -> viewModelScope.launch {
                (panel.toggleFolder(workingCopy, line.node.path) as? Result.Success)?.data?.let { saved ->
                    setState {
                        copy(
                            collapsed = saved.collapsed,
                            lines = model?.let { panelLines(it, tree, saved.collapsed) } ?: lines,
                        )
                    }
                }
            }

            is PanelLine.File -> openFile(line)
        }
    }

    private fun showStaged(path: String) {
        val row = currentState.model?.sections?.firstOrNull { it.kind == SectionKind.Staged }?.rows?.firstOrNull {
            it.path ==
                path
        }
        if (row == null) return
        val index = currentState.lines.indexOfFirst {
            it is PanelLine.File && it.section == SectionKind.Staged &&
                it.row.path == path
        }
        if (index >= 0) setState { copy(cursor = index) }
        openFile(PanelLine.File(SectionKind.Staged, row, 0, false))
    }

    private fun openFile(line: PanelLine.File) {
        val side = sideOf(line.section) ?: return
        val open = OpenFile(line.row, side)
        if (currentState.open != open) {
            setState {
                copy(open = open, body = null, ticks = SelectionState.EMPTY, ticked = emptySet(), anyway = false)
            }
        }
        shared.center.value = CenterView.Contributed(DIFF_CENTER, open.row.path, back = CenterView.Graph)
        readDiff(open)
    }

    // ---- writes --------------------------------------------------------------------------------------------------

    private fun sideOf(section: SectionKind): DiffSide? = when (section) {
        SectionKind.Staged -> DiffSide.STAGED
        SectionKind.Unstaged -> DiffSide.UNSTAGED
        else -> null
    }

    private fun sectionOf(line: PanelLine): SectionKind = when (line) {
        is PanelLine.File -> line.section
        is PanelLine.Folder -> line.section
        is PanelLine.Header -> line.section
    }

    private fun whole(rows: List<ChangeRow>) =
        ChangeSelection(rows.map { FileSelection(it.path, FileSelection.Scope.WholeFile, it.originalPath) })

    private suspend fun toggle(index: Int) {
        val s = currentState
        val line = s.lines.getOrNull(index) ?: return
        val model = s.model ?: return
        val side = sideOf(sectionOf(line)) ?: return
        val rows = filesOf(line, model)
        if (rows.isEmpty()) return
        send(side, whole(rows), rows.size)
    }

    /** Stage (from Unstaged) or unstage (from Staged) [selection], then read the lists again. */
    private suspend fun send(side: DiffSide, selection: ChangeSelection, files: Int) {
        if (currentState.busy != null) return
        val label = if (side == DiffSide.UNSTAGED) texts.staging(files) else texts.unstaging(files)
        setState { copy(busy = label, error = null) }
        val result =
            if (side ==
                DiffSide.UNSTAGED
            ) {
                staging.stage(workingCopy, selection)
            } else {
                staging.unstage(workingCopy, selection)
            }
        when (result) {
            is Result.Success -> {
                setState { copy(busy = null, ticks = SelectionState.EMPTY, ticked = emptySet()) }
                session.refresh(setOf(SnapshotPart.STATUS))
            }

            is Result.Failure -> setState { copy(busy = null, error = result.error) }
        }
    }

    private suspend fun applyTicks() {
        val s = currentState
        val open = s.open ?: return
        val info = infoOf(s) ?: return
        if (s.ticks.isEmpty) return
        send(open.side, s.ticks.toSelection(mapOf(open.row.path to info)), 1)
    }

    private suspend fun applyHunk(hunk: Int) {
        val open = currentState.open ?: return
        val info = infoOf(currentState) ?: return
        if (hunk !in info.hunks.indices) return
        val file = FileSelection(open.row.path, FileSelection.Scope.Hunks(setOf(hunk)), info.originalPath)
        send(open.side, ChangeSelection(listOf(file)), 1)
    }

    private fun askDiscard(index: Int) {
        val s = currentState
        val line = s.lines.getOrNull(index) ?: return
        val model = s.model ?: return
        if (sectionOf(line) != SectionKind.Unstaged) return
        val rows = filesOf(line, model)
        if (rows.isEmpty()) return
        val deletes = rows.any { it.status == RowStatus.UNTRACKED }
        setState { copy(discard = PendingDiscard(whole(rows), rows.map { it.path }, deletes)) }
    }

    private fun askDiscardTicks() {
        val s = currentState
        val open = s.open ?: return
        val info = infoOf(s) ?: return
        if (open.side != DiffSide.UNSTAGED || s.ticks.isEmpty) return
        val selection = s.ticks.toSelection(mapOf(open.row.path to info))
        setState {
            copy(discard = PendingDiscard(selection, listOf(open.row.path), deletes = false, lines = s.ticked.size))
        }
    }

    private suspend fun confirmDiscard() {
        val pending = currentState.discard ?: return
        if (currentState.busy != null) return
        val label = texts.discarding(pending.paths.size)
        setState { copy(discard = null, busy = label, error = null) }
        when (val r = staging.discard(workingCopy, pending.selection)) {
            is Result.Success -> {
                setState { copy(busy = null, ticks = SelectionState.EMPTY, ticked = emptySet()) }
                toast(
                    texts.discarded(pending.paths.singleOrNull() ?: texts.files(pending.paths.size), pending.lines),
                    r.data,
                )
                session.refresh(setOf(SnapshotPart.STATUS))
            }

            is Result.Failure -> setState { copy(busy = null, error = r.error) }
        }
    }

    private suspend fun undo() {
        val record = currentState.toast?.undo ?: return
        setState { copy(toast = null) }
        when (val r = staging.undoDiscard(workingCopy, record)) {
            is Result.Success -> {
                when (val outcome = r.data) {
                    UndoDiscardOutcome.Restored -> toast(texts.restored(), null)
                    UndoDiscardOutcome.Unavailable -> toast(texts.undoUnavailable(), null)
                    is UndoDiscardOutcome.ChangedSince -> toast(texts.changedSince(outcome.path), null)
                }
                session.refresh(setOf(SnapshotPart.STATUS))
            }

            is Result.Failure -> setState { copy(error = r.error) }
        }
    }

    /** Shows [text] for 10 seconds, with Undo when [undo] is given. */
    private fun toast(text: String, undo: DiscardRecord?) {
        val mine = ++stamp
        setState { copy(toast = Toast(text, undo, mine)) }
        toastJob?.cancel()
        toastJob =
            viewModelScope.launch {
                delay(TOAST_MS)
                if (currentState.toast?.stamp == mine) setState { copy(toast = null) }
            }
    }

    internal companion object {
        /** The id of the center view this panel contributes (`CenterView.Contributed`). */
        const val DIFF_CENTER: String = "working-diff"
        const val TOAST_MS: Long = 10_000
        private const val SHORT = 7
    }
}

/** The sentences the view model puts in toasts and the status line; the page passes localized ones. */
internal interface ChangesTexts {
    fun detached(short: String): String

    fun staging(files: Int): String

    fun unstaging(files: Int): String

    fun discarding(files: Int): String

    fun files(count: Int): String

    fun discarded(what: String, lines: Int): String

    fun restored(): String

    fun undoUnavailable(): String

    fun changedSince(path: String): String

    object English : ChangesTexts {
        override fun detached(short: String) = "detached at $short"

        override fun staging(files: Int) = "Staging ${files(files)}…"

        override fun unstaging(files: Int) = "Unstaging ${files(files)}…"

        override fun discarding(files: Int) = "Discarding ${files(files)}…"

        override fun files(count: Int) = if (count == 1) "1 file" else "$count files"

        override fun discarded(what: String, lines: Int) =
            if (lines > 0) "Discarded $lines lines in $what" else "Discarded $what"

        override fun restored() = "Restored what was discarded"

        override fun undoUnavailable() = "Nothing to restore from. Local History has what it kept."

        override fun changedSince(path: String) = "$path changed since. Restore from Local History instead."
    }
}
