// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.presentation.home

import androidx.lifecycle.viewModelScope
import com.srctool.graft.data.app.api.models.KnownRepository
import com.srctool.graft.data.app.api.repositories.KnownRepositoryRepository
import com.srctool.graft.data.app.api.repositories.SettingsRepository
import com.srctool.graft.data.app.api.repositories.WorkspaceRepository
import com.srctool.graft.feature.home.domain.api.models.OpenOutcome
import com.srctool.graft.feature.home.domain.api.usecases.OpenRepositoryUseCase
import com.srctool.graft.feature.home.domain.api.usecases.RepositorySummaryUseCase
import com.srctool.graft.feature.home.domain.api.usecases.ScanFolderUseCase
import com.srctool.sinew.models.Result
import com.srctool.sinew.viewmodel.EventActionViewModel
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * Home (S1): the known repositories and workspaces, with each row's summary read as it comes into view. Opening goes
 * through [open] (which finds the repository a folder belongs to and records it), and a repository or workspace that's
 * opened is handed to the host as an effect. Writes to a list a newer Graft saved are refused by the data layer and
 * come back as a problem. [clock] gives epoch milliseconds.
 */
internal class HomeViewModel(
    private val open: OpenRepositoryUseCase,
    private val scan: ScanFolderUseCase,
    private val summaries: RepositorySummaryUseCase,
    private val known: KnownRepositoryRepository,
    private val workspaces: WorkspaceRepository,
    private val settings: SettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : EventActionViewModel<HomeState, HomeEvent, HomeAction, HomeEffect>(HomeState()) {
    private var opened = false

    override fun mapEventToAction(event: HomeEvent): HomeAction? = when (event) {
        HomeEvent.Opened -> if (opened) null else HomeAction.Start.also { opened = true }

        HomeEvent.Retry -> HomeAction.Reload

        is HomeEvent.ViewChosen -> HomeAction.Local { it.copy(view = event.view, focus = null, query = "") }

        is HomeEvent.QueryTyped -> HomeAction.Local { it.copy(query = event.query) }

        is HomeEvent.RowShown -> HomeAction.Summarize(event.path)

        is HomeEvent.Focused -> HomeAction.Local { it.copy(focus = event.key) }

        is HomeEvent.Moved ->
            HomeAction.Local { s ->
                val rows = s.rows
                if (rows.isEmpty()) {
                    s
                } else {
                    val at = s.focusIndex.takeIf { it >= 0 } ?: if (event.by > 0) -1 else rows.size
                    s.copy(focus = rows[(at + event.by).coerceIn(0, rows.lastIndex)].key)
                }
            }

        is HomeEvent.OpenFolder -> HomeAction.Open(event.folder)

        HomeEvent.OpenFocused -> HomeAction.OpenFocused

        HomeEvent.OpenAll -> HomeAction.OpenAll

        is HomeEvent.PinToggled -> HomeAction.Pin(event.path)

        HomeEvent.PinFocused -> HomeAction.Pin(null)

        is HomeEvent.RemoveAsked -> HomeAction.AskRemove(event.path)

        HomeEvent.RemoveFocusedAsked -> HomeAction.AskRemove(null)

        HomeEvent.RemoveConfirmed -> HomeAction.ConfirmRemove

        is HomeEvent.Located -> HomeAction.Locate(event.from, event.to)

        is HomeEvent.DialogSet -> HomeAction.Local { it.copy(dialog = event.dialog) }

        HomeEvent.DialogClosed -> HomeAction.Local { it.copy(dialog = HomeDialog.None) }

        HomeEvent.TrustConfirmed -> HomeAction.Trust

        is HomeEvent.ProblemOutputShown ->
            HomeAction.Local { s ->
                (s.dialog as? HomeDialog.Problem)?.let { s.copy(dialog = it.copy(outputShown = event.shown)) } ?: s
            }

        is HomeEvent.ScanFolderChosen -> HomeAction.Scan(event.folder)

        is HomeEvent.ScanToggled ->
            HomeAction.Local { s ->
                val d = s.dialog as? HomeDialog.Scan ?: return@Local s
                s.copy(
                    dialog = d.copy(
                        selected = if (event.path in
                            d.selected
                        ) {
                            d.selected.minusElement(event.path)
                        } else {
                            d.selected.plusElement(event.path)
                        },
                    ),
                )
            }

        HomeEvent.ScanAllToggled ->
            HomeAction.Local { s ->
                val d = s.dialog as? HomeDialog.Scan ?: return@Local s
                val all = d.found.map { it.path }.toSet()
                s.copy(dialog = d.copy(selected = if (d.selected == all) emptySet() else all))
            }

        HomeEvent.ScanConfirmed -> HomeAction.AddScanned

        HomeEvent.NewWorkspaceConfirmed -> HomeAction.CreateWorkspace

        HomeEvent.RenameConfirmed -> HomeAction.RenameWorkspace

        HomeEvent.DeleteWorkspaceConfirmed -> HomeAction.DeleteWorkspace

        is HomeEvent.AddToWorkspaceToggled ->
            HomeAction.Local { s ->
                val d = s.dialog as? HomeDialog.AddToWorkspace ?: return@Local s
                s.copy(
                    dialog = d.copy(
                        selected = if (event.path in
                            d.selected
                        ) {
                            d.selected.minusElement(event.path)
                        } else {
                            d.selected.plusElement(event.path)
                        },
                    ),
                )
            }

        HomeEvent.AddToWorkspaceConfirmed -> HomeAction.AddToWorkspace

        is HomeEvent.FolderAddedToWorkspace -> HomeAction.AddFolderToWorkspace(event.id, event.folder)

        is HomeEvent.WorkspaceRepositoryRemoved -> HomeAction.RemoveFromWorkspace(event.id, event.path)

        is HomeEvent.Created -> HomeAction.Created(event.root)
    }

    override suspend fun onAction(action: HomeAction) {
        when (action) {
            HomeAction.Start -> {
                load()
                welcomeOnFirstRun()
            }

            HomeAction.Reload -> load()

            is HomeAction.Local -> setState(action.change)

            is HomeAction.Summarize -> summarize(action.path)

            is HomeAction.Open -> open(action.folder)

            HomeAction.OpenFocused -> currentState.rows.firstOrNull {
                it.key == currentState.focus
            }?.let { open(it.path) }

            HomeAction.OpenAll -> {
                val roots = currentState.rows.filterNot { it.missing }.map { it.path }
                if (roots.isNotEmpty()) emitEffect(HomeEffect.OpenAll(roots))
            }

            is HomeAction.Pin -> {
                val path = action.path ?: currentState.rows.firstOrNull { it.key == currentState.focus }?.path ?: return
                val pinned = currentState.repositories.firstOrNull { it.path == path }?.pinned ?: return
                changed(known.setPinned(path, !pinned))
            }

            is HomeAction.AskRemove -> {
                val path = action.path ?: currentState.rows.firstOrNull { it.key == currentState.focus }?.path ?: return
                setState { copy(dialog = HomeDialog.RemoveRepository(path)) }
            }

            HomeAction.ConfirmRemove -> {
                val d = currentState.dialog as? HomeDialog.RemoveRepository ?: return
                setState { copy(dialog = HomeDialog.None) }
                changed(known.remove(d.path))
            }

            is HomeAction.Locate -> changed(known.relocate(action.from, action.to))

            HomeAction.Trust -> {
                val d = currentState.dialog as? HomeDialog.Trust ?: return
                setState { copy(dialog = HomeDialog.None) }
                outcome(open.trustAndOpen(d.outcome, d.folder), d.folder)
            }

            is HomeAction.Scan -> scanFolder(action.folder)

            HomeAction.AddScanned -> addScanned()

            HomeAction.CreateWorkspace -> {
                val d = currentState.dialog as? HomeDialog.NewWorkspace ?: return
                setState { copy(dialog = HomeDialog.None) }
                when (val r = workspaces.create(d.name.trim())) {
                    is Result.Success -> {
                        load()
                        setState { copy(view = HomeView.Workspace(r.data.id), focus = null) }
                    }

                    is Result.Failure -> problem(r.error)
                }
            }

            HomeAction.RenameWorkspace -> {
                val d = currentState.dialog as? HomeDialog.RenameWorkspace ?: return
                setState { copy(dialog = HomeDialog.None) }
                changed(workspaces.rename(d.id, d.name.trim()))
            }

            HomeAction.DeleteWorkspace -> {
                val d = currentState.dialog as? HomeDialog.DeleteWorkspace ?: return
                setState {
                    copy(
                        dialog = HomeDialog.None,
                        view = if ((view as? HomeView.Workspace)?.id ==
                            d.id
                        ) {
                            HomeView.All
                        } else {
                            view
                        },
                    )
                }
                changed(workspaces.delete(d.id))
            }

            HomeAction.AddToWorkspace -> {
                val d = currentState.dialog as? HomeDialog.AddToWorkspace ?: return
                setState { copy(dialog = HomeDialog.None) }
                if (d.selected.isNotEmpty()) changed(workspaces.add(d.id, d.selected.toList()))
            }

            is HomeAction.AddFolderToWorkspace -> addFolderToWorkspace(action.id, action.folder)

            is HomeAction.RemoveFromWorkspace -> changed(workspaces.removeRepository(action.id, action.path))

            is HomeAction.Created -> {
                load()
                emitEffect(HomeEffect.Open(action.root))
            }
        }
    }

    /** The welcome steps open by themselves until they've been finished or closed once (Onboarding). */
    private suspend fun welcomeOnFirstRun() {
        val onboarded = (settings.read() as? Result.Success)?.data?.value?.onboarded ?: return
        if (!onboarded && currentState.dialog == HomeDialog.None) setState { copy(dialog = HomeDialog.Welcome) }
    }

    private suspend fun load() {
        val list = known.list()
        val spaces = workspaces.list()
        val failure = (list as? Result.Failure)?.error ?: (spaces as? Result.Failure)?.error
        if (failure != null) {
            setState { copy(phase = HomeState.Phase.Failed, error = failure) }
            return
        }
        val repos = (list as Result.Success).data
        val saved = (spaces as Result.Success).data
        setState {
            copy(
                phase = HomeState.Phase.Ready,
                error = null,
                repositories = repos.value,
                workspaces = saved.value,
                readOnly = repos.readOnly || saved.readOnly,
                // A deleted workspace's view falls back to All.
                view = if (view is HomeView.Workspace &&
                    saved.value.none { it.id == (view as HomeView.Workspace).id }
                ) {
                    HomeView.All
                } else {
                    view
                },
                now = clock() / 1000,
            )
        }
    }

    /** A write's result: the lists are read again, or the failure is shown. */
    private suspend fun changed(result: Result<*>) {
        when (result) {
            is Result.Success -> load()
            is Result.Failure -> problem(result.error)
        }
    }

    private fun problem(error: Throwable, text: String? = null) {
        setState { copy(dialog = HomeDialog.Problem(error, text)) }
    }

    private fun summarize(path: Path) {
        val row = currentState.rows.firstOrNull { it.path == path } ?: return
        if (row.missing || path in currentState.summaries) return
        setState { copy(summaries = summaries + (path to SummaryState.Loading)) }
        viewModelScope.launch {
            val state =
                when (val r = summaries.summarize(path)) {
                    is Result.Success -> SummaryState.Loaded(r.data)
                    is Result.Failure -> SummaryState.Unavailable(r.error)
                }
            setState { copy(summaries = summaries + (path to state)) }
        }
    }

    private suspend fun open(folder: Path) = outcome(open.open(folder), folder)

    private suspend fun outcome(result: OpenOutcome, folder: Path) {
        when (result) {
            is OpenOutcome.Opened -> {
                emitEffect(HomeEffect.Open(result.root, result.subfolder))
                load()
            }

            is OpenOutcome.NotARepository -> setState { copy(dialog = HomeDialog.NotARepository(result.folder)) }

            is OpenOutcome.FolderMissing -> setState {
                copy(dialog = HomeDialog.Problem(null, "missing:${result.folder}"))
            }

            is OpenOutcome.Untrusted -> setState { copy(dialog = HomeDialog.Trust(result, folder)) }

            is OpenOutcome.Failed -> problem(result.error)
        }
    }

    private suspend fun scanFolder(folder: Path) {
        setState { copy(dialog = HomeDialog.Scan(folder)) }
        when (val r = scan.scan(folder)) {
            is Result.Success -> {
                val found = r.data
                // What Home doesn't list yet is checked; what it does is shown, unchecked.
                setState {
                    copy(
                        dialog = HomeDialog.Scan(
                            folder,
                            scanning = false,
                            found = found,
                            selected = found.filterNot {
                                it.known
                            }.map { it.path }.toSet(),
                        ),
                    )
                }
            }

            is Result.Failure -> problem(r.error)
        }
    }

    private suspend fun addScanned() {
        val d = currentState.dialog as? HomeDialog.Scan ?: return
        val paths = d.selected.toList()
        setState { copy(dialog = HomeDialog.None) }
        if (paths.isEmpty()) return
        // Home lists what was found by "opening" it into the recent list, then an optional workspace holds them.
        for (path in paths) {
            val r = known.opened(path)
            if (r is Result.Failure) return problem(r.error)
        }
        val name = d.workspaceName.trim()
        if (name.isNotEmpty()) {
            when (val r = scan.addAsWorkspace(name, paths)) {
                is Result.Success -> {
                    load()
                    setState { copy(view = HomeView.Workspace(r.data.id), focus = null) }
                }

                is Result.Failure -> problem(r.error)
            }
        } else {
            load()
        }
    }

    /** A folder picked for a workspace: the repository it belongs to joins it (and Home's list). */
    private suspend fun addFolderToWorkspace(id: String, folder: Path) {
        when (val r = open.open(folder)) {
            is OpenOutcome.Opened -> changed(workspaces.add(id, listOf(r.root)))
            is OpenOutcome.NotARepository -> setState { copy(dialog = HomeDialog.NotARepository(r.folder)) }
            is OpenOutcome.FolderMissing -> setState { copy(dialog = HomeDialog.Problem(null, "missing:${r.folder}")) }
            is OpenOutcome.Untrusted -> setState { copy(dialog = HomeDialog.Trust(r, folder)) }
            is OpenOutcome.Failed -> problem(r.error)
        }
    }

    /** Repositories not in any workspace's list, for the add dialog. */
    internal fun candidates(id: String): List<KnownRepository> {
        val members = currentState.workspaces.firstOrNull { it.id == id }?.repositories.orEmpty().toSet()
        return currentState.repositories.filter { it.path !in members && !it.missing }
    }
}
