// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.presentation.commit

import androidx.lifecycle.viewModelScope
import com.srctool.graft.data.app.api.models.ChangesMode
import com.srctool.graft.data.git.api.models.ChangeSelection
import com.srctool.graft.data.git.api.models.CoAuthor
import com.srctool.graft.data.git.api.models.CommitDraft
import com.srctool.graft.data.git.api.models.CommitMessageText
import com.srctool.graft.data.git.api.models.FileSelection
import com.srctool.graft.data.git.api.models.GuardSeverity
import com.srctool.graft.feature.workingcopy.domain.api.models.ChangesModel
import com.srctool.graft.feature.workingcopy.domain.api.models.SectionKind
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingCopy
import com.srctool.graft.feature.workingcopy.domain.api.usecases.CommitChangesUseCase
import com.srctool.graft.feature.workingcopy.domain.api.usecases.CommitOutcome
import com.srctool.graft.feature.workingcopy.domain.api.usecases.CommitRequest
import com.srctool.graft.feature.workingcopy.domain.api.usecases.DraftKey
import com.srctool.graft.feature.workingcopy.domain.api.usecases.DraftsUseCase
import com.srctool.graft.feature.workingcopy.domain.api.usecases.StageChangesUseCase
import com.srctool.graft.feature.workingcopy.domain.api.usecases.UndoRedoUseCase
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.session.SnapshotPart
import com.srctool.sinew.models.Result
import com.srctool.sinew.viewmodel.EventActionViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The commit box (S9, Task 13): the draft kept per branch, Amend, the options, the guard dialog's answers, and Undo.
 * It commits what is in the index and nothing else. A commit the guards stop changes nothing: the dialog opens over an
 * untouched box and index. [lists] is the Changes panel's model, for the staged count and conflicts; [onShow] opens a
 * file's staged diff (Open at line). Sentences come from [texts].
 */
internal class CommitViewModel(
    private val workingCopy: WorkingCopy,
    private val session: RepositorySession,
    private val commits: CommitChangesUseCase,
    private val drafts: DraftsUseCase,
    private val staging: StageChangesUseCase,
    private val undo: UndoRedoUseCase,
    private val lists: Flow<ChangesModel?>,
    private val onShow: (path: String) -> Unit,
    private val texts: CommitTexts = CommitTexts.English,
) : EventActionViewModel<CommitState, CommitEvent, CommitAction, CommitEffect>(CommitState()) {
    private var started = false
    private var key: DraftKey? = null
    private var saveJob: Job? = null
    private var toastJob: Job? = null
    private var stamp = 0L

    /** What was typed before Amend loaded the last message, so unticking brings it back. */
    private var stashed: Pair<String, String>? = null

    override fun mapEventToAction(event: CommitEvent): CommitAction? = when (event) {
        CommitEvent.Opened -> if (started) null else CommitAction.Start.also { started = true }

        is CommitEvent.SummaryChanged -> CommitAction.Edited { it.copy(summary = event.text.lineSequence().first()) }

        is CommitEvent.DescriptionChanged -> CommitAction.Edited { it.copy(description = event.text) }

        is CommitEvent.AmendToggled -> CommitAction.Amend(event.on)

        is CommitEvent.SignOffToggled -> CommitAction.Local { it.copy(signOff = event.on) }

        is CommitEvent.NoVerifyToggled -> CommitAction.Local { it.copy(noVerify = event.on) }

        is CommitEvent.AllowEmptyToggled -> CommitAction.Local { it.copy(allowEmpty = event.on) }

        is CommitEvent.OptionsShown -> CommitAction.Local { it.copy(optionsOpen = event.open) }

        is CommitEvent.CoAuthorTyped -> CommitAction.Local {
            it.copy(coAuthorInput = event.text, coAuthorInvalid = false)
        }

        is CommitEvent.CoAuthorAdded -> CommitAction.AddAuthor(event.author)

        is CommitEvent.CoAuthorRemoved ->
            CommitAction.Local { it.copy(coAuthors = it.coAuthors.filterIndexed { i, _ -> i != event.index }) }

        CommitEvent.Submitted -> CommitAction.Submit

        is CommitEvent.AllowOnce -> CommitAction.Allow(event.fingerprint)

        is CommitEvent.AllowAlwaysAsked -> CommitAction.Local {
            it.copy(guard = it.guard?.copy(asking = event.fingerprint))
        }

        is CommitEvent.AllowAlways -> CommitAction.Always(event.fingerprint, event.shared)

        is CommitEvent.UnstageFile -> CommitAction.Unstage(event.path)

        is CommitEvent.OpenAtLine -> CommitAction.Show(event.path)

        CommitEvent.CommitAnyway -> CommitAction.Anyway

        CommitEvent.GuardCancelled -> CommitAction.Local { it.copy(guard = null) }

        CommitEvent.UndoClicked -> CommitAction.Undo

        CommitEvent.ToastDismissed -> CommitAction.Local { it.copy(toast = null) }

        CommitEvent.ErrorDismissed -> CommitAction.Local { it.copy(error = null) }

        is CommitEvent.OutputShown -> CommitAction.Local { it.copy(outputShown = event.shown) }
    }

    override suspend fun onAction(action: CommitAction) {
        when (action) {
            CommitAction.Start -> start()

            is CommitAction.Local -> setState(action.change)

            is CommitAction.Edited -> {
                setState(action.change)
                scheduleSave()
            }

            is CommitAction.Amend -> amend(action.on)

            is CommitAction.AddAuthor -> addAuthor(action.author)

            CommitAction.Submit -> submit(emptySet())

            is CommitAction.Allow -> setState {
                copy(guard = guard?.copy(answered = guard.answered + action.fingerprint))
            }

            is CommitAction.Always -> always(action.fingerprint, action.shared)

            is CommitAction.Unstage -> unstage(action.path)

            is CommitAction.Show -> {
                setState { copy(guard = null) }
                onShow(action.path)
            }

            CommitAction.Anyway -> anyway()

            CommitAction.Undo -> undoLast()
        }
    }

    // ---- opening -------------------------------------------------------------------------------------------------

    private fun start() {
        viewModelScope.launch {
            lists.collect { model ->
                val staged = model?.sections?.firstOrNull { it.kind == SectionKind.Staged }?.rows?.size ?: 0
                val conflicts = model?.sections?.firstOrNull { it.kind == SectionKind.Conflicts }?.rows?.size ?: 0
                setState { copy(loaded = model != null, staged = staged, conflicts = conflicts) }
            }
        }
        viewModelScope.launch {
            // The branch changed: the old draft is saved under its branch and the new branch's comes back.
            session.snapshot.map { it.head }.distinctUntilChanged().collect { loadDraft() }
        }
        viewModelScope.launch { loadPrevious() }
        viewModelScope.launch {
            (commits.recentAuthors(workingCopy) as? Result.Success)?.data?.let { recent ->
                setState { copy(recent = recent) }
            }
        }
    }

    private suspend fun loadDraft() {
        saveNow()
        val next = (drafts.key(workingCopy, ChangesMode.STAGING_AREA) as? Result.Success)?.data ?: return
        key = next
        if (currentState.amend) return
        val text = (drafts.read(workingCopy, next) as? Result.Success)?.data.orEmpty()
        if (text.isNotBlank()) {
            val message = CommitMessageText.of(text)
            setState { copy(summary = message.summary, description = message.description) }
        } else if (currentState.summary.isBlank()) {
            // An empty draft starts from `commit.template`, when the repository has one.
            (commits.template(workingCopy) as? Result.Success)?.data?.takeIf { it.isNotBlank() }?.let { template ->
                val message = CommitMessageText.of(template)
                setState { copy(summary = message.summary, description = message.description) }
            }
        }
    }

    private suspend fun loadPrevious() {
        val previous = (commits.previousMessage(workingCopy) as? Result.Success)?.data
        setState { copy(previous = previous) }
    }

    // ---- the draft -----------------------------------------------------------------------------------------------

    private fun scheduleSave() {
        if (currentState.amend) return
        saveJob?.cancel()
        saveJob =
            viewModelScope.launch {
                delay(SAVE_MS)
                saveNow()
            }
    }

    /** Saves the text of the box, unless it is an amend's (that message belongs to the last commit, not the draft). */
    private suspend fun saveNow() {
        val k = key ?: return
        val s = currentState
        if (s.amend) return
        val text = if (s.description.isBlank()) s.summary.trim() else s.summary.trim() + "\n\n" + s.description.trim()
        drafts.write(workingCopy, k, text)
    }

    // ---- amend and co-authors ------------------------------------------------------------------------------------

    private fun amend(on: Boolean) {
        val s = currentState
        if (on == s.amend) return
        if (on) {
            val previous = s.previous ?: return
            stashed = s.summary to s.description
            setState { copy(amend = true, summary = previous.summary, description = previous.description) }
        } else {
            val back = stashed ?: ("" to "")
            stashed = null
            setState { copy(amend = false, summary = back.first, description = back.second) }
        }
        emitEffect(CommitEffect.FocusSummary)
    }

    private fun addAuthor(picked: CoAuthor?) {
        val author = picked ?: CoAuthor.parse(currentState.coAuthorInput)
        if (author == null) {
            if (currentState.coAuthorInput.isNotBlank()) setState { copy(coAuthorInvalid = true) }
            return
        }
        setState {
            copy(
                coAuthors = if (author in coAuthors) coAuthors else coAuthors + author,
                coAuthorInput = "",
                coAuthorInvalid = false,
            )
        }
    }

    // ---- committing ----------------------------------------------------------------------------------------------

    private suspend fun submit(allowOnce: Set<String>) {
        val s = currentState
        if (s.blocker != null) return
        setState { copy(busy = if (amend) texts.amending() else texts.committing(staged), error = null) }
        val request =
            CommitRequest(
                draft = CommitDraft(
                    summary = s.summary,
                    description = s.description,
                    signOff = s.signOff,
                    coAuthors = s.coAuthors,
                    noVerify = s.noVerify,
                    allowEmpty = s.allowEmpty,
                ),
                allowOnce = allowOnce,
                clearDraft = if (s.amend) null else key,
            )
        val result =
            if (s.amend) {
                commits.amend(
                    workingCopy,
                    request,
                    withStaged = s.staged > 0,
                )
            } else {
                commits.commit(workingCopy, request)
            }
        when (result) {
            is Result.Failure -> setState { copy(busy = null, error = result.error) }

            is Result.Success -> when (val outcome = result.data) {
                is CommitOutcome.Committed -> {
                    saveJob?.cancel()
                    stashed = null
                    setState {
                        copy(
                            busy = null,
                            guard = null,
                            summary = "",
                            description = "",
                            amend = false,
                            noVerify = false,
                            allowEmpty = false,
                            coAuthors = emptyList(),
                            optionsOpen = false,
                        )
                    }
                    toast(
                        if (s.amend) {
                            texts.amended(
                                outcome.id.hex.take(SHORT),
                            )
                        } else {
                            texts.committed(outcome.id.hex.take(SHORT))
                        },
                        undo = true,
                    )
                    session.refresh(setOf(SnapshotPart.STATUS, SnapshotPart.REFS))
                    loadPrevious()
                }

                is CommitOutcome.Stopped -> setState {
                    copy(busy = null, guard = GuardDialog(outcome.report.findings, amend = s.amend))
                }
            }
        }
    }

    private suspend fun anyway() {
        val dialog = currentState.guard ?: return
        if (!dialog.canProceed) return
        // Warnings never need an answer, so going on is what answers them.
        val warned = dialog.findings.filter { it.severity == GuardSeverity.WARN }.map { it.fingerprint }
        submit(dialog.answered + warned)
    }

    private suspend fun always(fingerprint: String, shared: Boolean) {
        val finding = currentState.guard?.findings?.firstOrNull { it.fingerprint == fingerprint } ?: return
        when (val r = commits.allowAlways(workingCopy, finding, shared)) {
            is Result.Success -> dropFinding { it.fingerprint == fingerprint }
            is Result.Failure -> setState { copy(guard = guard?.copy(asking = null), error = r.error) }
        }
    }

    private suspend fun unstage(path: String) {
        val selection = ChangeSelection(listOf(FileSelection(path, FileSelection.Scope.WholeFile)))
        when (val r = staging.unstage(workingCopy, selection)) {
            is Result.Success -> {
                session.refresh(setOf(SnapshotPart.STATUS))
                dropFinding { it.path == path }
            }

            is Result.Failure -> setState { copy(error = r.error) }
        }
    }

    /** Takes the findings [match] out of the dialog; with none left it closes, and Commit has to be pressed again. */
    private fun dropFinding(match: (com.srctool.graft.data.git.api.models.GuardFinding) -> Boolean) {
        val dialog = currentState.guard ?: return
        val left = dialog.findings.filterNot(match)
        if (left.isEmpty()) {
            setState { copy(guard = null) }
            toast(texts.guardsAnswered(), undo = false)
        } else {
            setState { copy(guard = dialog.copy(findings = left, asking = null)) }
        }
    }

    private suspend fun undoLast() {
        setState { copy(toast = null) }
        when (val r = undo.undo(workingCopy)) {
            is Result.Success -> {
                toast(texts.undone(), undo = false)
                session.refresh(setOf(SnapshotPart.STATUS, SnapshotPart.REFS))
                loadPrevious()
            }

            is Result.Failure -> setState { copy(error = r.error) }
        }
    }

    private fun toast(text: String, undo: Boolean) {
        val mine = ++stamp
        setState { copy(toast = CommitToast(text, undo, mine)) }
        toastJob?.cancel()
        toastJob =
            viewModelScope.launch {
                delay(TOAST_MS)
                if (currentState.toast?.stamp == mine) setState { copy(toast = null) }
            }
    }

    internal companion object {
        const val SAVE_MS: Long = 500
        const val TOAST_MS: Long = 10_000
        private const val SHORT = 7
    }
}

/** The sentences the commit view model puts in toasts and the status line. */
internal interface CommitTexts {
    fun committing(files: Int): String

    fun amending(): String

    fun committed(short: String): String

    fun amended(short: String): String

    fun undone(): String

    fun guardsAnswered(): String

    object English : CommitTexts {
        override fun committing(files: Int) = if (files == 1) "Committing 1 file…" else "Committing $files files…"

        override fun amending() = "Amending…"

        override fun committed(short: String) = "Committed $short"

        override fun amended(short: String) = "Amended $short"

        override fun undone() = "Undone"

        override fun guardsAnswered() = "Every finding is answered. Press Commit to go on."
    }
}
