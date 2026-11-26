// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.presentation.changes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.srctool.graft.core.ui.components.DockSide
import com.srctool.graft.core.ui.components.ToolWindow
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.GraftIcon
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingCopy
import com.srctool.graft.feature.workingcopy.presentation.commit.CommitBoxPage
import com.srctool.graft.feature.workingcopy.presentation.commit.CommitEvent
import com.srctool.graft.feature.workingcopy.presentation.commit.CommitViewModel
import com.srctool.graft.feature.workingcopy.presentation.history.HistoryEvent
import com.srctool.graft.feature.workingcopy.presentation.history.HistoryPage
import com.srctool.graft.feature.workingcopy.presentation.history.HistoryViewModel
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.view.CenterView
import com.srctool.graft.presentation.view.RepositoryViewState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.nio.file.Path

/**
 * What the working copy adds to a repository tab (E3): its Changes tool window, and the center view that shows a
 * file's diff. `:module:navigation` hands both to the repository screen, since features never import each other.
 */
public class WorkingCopyContributions internal constructor(
    public val window: ToolWindow,
    /** What each `CenterView.Contributed` id draws, given its key: the working diff and Local History. */
    public val centers: Map<String, @Composable (key: String) -> Unit>,
)

/** The id of the Changes tool window; the saved dock layout is keyed by it. */
public const val CHANGES_WINDOW: String = "changes"

/**
 * The contributions for the repository of [session], or null for a bare repository (it has no working tree). One view
 * model serves the window and the center, and is cleared when the tab goes. [onOpenRepository] opens a submodule in
 * its own tab.
 */
@Composable
public fun rememberWorkingCopyContributions(
    session: RepositorySession,
    shared: RepositoryViewState,
    onOpenRepository: (Path) -> Unit,
): WorkingCopyContributions? {
    val workTree = session.location.workTree ?: return null
    val owner = remember(session.location) {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val copy = remember(workTree) { WorkingCopy(workTree, workTree.fileName?.toString() ?: workTree.toString()) }
    val title = graftText("changes.title", "Changes")
    val viewModel = koinViewModel<ChangesViewModel>(viewModelStoreOwner = owner) { parametersOf(copy, session, shared) }
    // The commit box reads the lists the panel shows, and asks the panel to show a file (Open at line).
    val lists = remember(viewModel) { viewModel.state.map { it.model }.distinctUntilChanged() }
    val commit =
        koinViewModel<CommitViewModel>(viewModelStoreOwner = owner) {
            parametersOf(copy, session, lists, { path: String -> viewModel.onEvent(ChangesEvent.StagedShown(path)) })
        }
    val summaryFocus = remember(commit) { FocusRequester() }
    val history = koinViewModel<HistoryViewModel>(viewModelStoreOwner = owner) { parametersOf(copy, session, shared) }
    return remember(viewModel, commit, history, workTree, title) {
        // Local History for one file, or the whole working copy (an empty key).
        val showHistory = { path: String? ->
            shared.center.value =
                CenterView.Contributed(HistoryViewModel.CENTER, path.orEmpty(), back = CenterView.Graph)
        }
        WorkingCopyContributions(
            window = ToolWindow(CHANGES_WINDOW, title, GraftIcon.COMMIT, DockSide.RIGHT) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        ChangesPage(
                            viewModel,
                            onOpenSubmodule = { path -> onOpenRepository(workTree.resolve(path)) },
                            onShowHistory = showHistory,
                            onSnapshotNow = {
                                // The dialog and its toast are Local History's, so the screen comes up with it.
                                val center = shared.center.value
                                val showing = center is CenterView.Contributed && center.id == HistoryViewModel.CENTER
                                if (!showing) showHistory(null)
                                history.onEvent(HistoryEvent.SnapshotAsked)
                            },
                            onCommitKey = { amend ->
                                if (amend) commit.onEvent(CommitEvent.AmendToggled(true))
                                runCatching { summaryFocus.requestFocus() }
                            },
                        )
                    }
                    CommitBoxPage(commit, summaryFocus)
                }
            },
            centers = mapOf(
                ChangesViewModel.DIFF_CENTER to { _ -> WorkingDiffPage(viewModel) },
                HistoryViewModel.CENTER to { key -> HistoryPage(history, key.ifEmpty { null }) },
            ),
        )
    }
}
