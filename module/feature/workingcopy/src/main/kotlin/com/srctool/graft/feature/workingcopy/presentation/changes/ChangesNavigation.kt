// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.presentation.changes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.srctool.graft.core.ui.components.DockSide
import com.srctool.graft.core.ui.components.ToolWindow
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.GraftIcon
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingCopy
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.view.RepositoryViewState
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.nio.file.Path

/**
 * What the working copy adds to a repository tab (E3): its Changes tool window, and the center view that shows a
 * file's diff. `:module:navigation` hands both to the repository screen, since features never import each other.
 */
public class WorkingCopyContributions internal constructor(
    public val window: ToolWindow,
    /** The `CenterView.Contributed` id [center] draws. */
    public val centerId: String,
    public val center: @Composable (key: String) -> Unit,
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
    return remember(viewModel, workTree, title) {
        WorkingCopyContributions(
            window = ToolWindow(CHANGES_WINDOW, title, GraftIcon.COMMIT, DockSide.RIGHT) {
                ChangesPage(viewModel) { path -> onOpenRepository(workTree.resolve(path)) }
            },
            centerId = ChangesViewModel.DIFF_CENTER,
            center = { _ -> WorkingDiffPage(viewModel) },
        )
    }
}
