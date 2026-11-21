// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.graft.core.ui.components.DockLayout
import com.srctool.graft.core.ui.components.DockLayoutState
import com.srctool.graft.core.ui.components.DockSide
import com.srctool.graft.core.ui.components.ToolWindow
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.GraftIcon
import com.srctool.graft.data.app.api.repositories.RepositoryUiStateRepository
import com.srctool.graft.feature.graph.presentation.branches.BranchesDestination
import com.srctool.graft.feature.graph.presentation.branches.BranchesRoute
import com.srctool.graft.feature.graph.presentation.compare.CompareDestination
import com.srctool.graft.feature.graph.presentation.compare.CompareRoute
import com.srctool.graft.feature.graph.presentation.details.DetailsDestination
import com.srctool.graft.feature.graph.presentation.details.DetailsRoute
import com.srctool.graft.feature.graph.presentation.diff.DiffDestination
import com.srctool.graft.feature.graph.presentation.diff.DiffRoute
import com.srctool.graft.feature.graph.presentation.gitlog.GitLogDestination
import com.srctool.graft.feature.graph.presentation.gitlog.GitLogRoute
import com.srctool.graft.feature.graph.presentation.graph.GraphDestination
import com.srctool.graft.feature.graph.presentation.graph.GraphRoute
import com.srctool.graft.feature.graph.presentation.operations.OperationsDestination
import com.srctool.graft.feature.graph.presentation.operations.OperationsRoute
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.view.CenterView
import com.srctool.graft.presentation.view.RepositoryViewState
import org.koin.compose.koinInject

/**
 * One open repository's panes (the main window's tab for it): the left panel, the graph or the diff, commit details and
 * the Operations and Git log tool windows in a [DockLayout], sharing a [RepositoryViewState]. [dock] belongs to the
 * host, so its shortcuts and focus mode reach it; the screen restores the repository's saved layout into it and saves
 * the user's changes (`state.json`). [onViewState] hands the host the shared state while the screen is shown. The graph
 * stays composed under a diff, so going back keeps its selection and scroll.
 */
@Composable
public fun RepositoryScreen(
    session: RepositorySession,
    onViewState: (RepositoryViewState?) -> Unit = {},
    dock: DockLayoutState = remember(session.location) { defaultDock() },
) {
    val location = session.location
    val folder = (location.workTree ?: location.gitDir).toString()
    val uiState = koinInject<RepositoryUiStateRepository>()
    val shared = remember(location) { RepositoryViewState(location.commonDir, uiState) }
    // The host (Search Everywhere) selects commits through the same state the panes share.
    DisposableEffect(shared) {
        onViewState(shared)
        onDispose { onViewState(null) }
    }
    PanelLayoutKeeper(dock, location.commonDir, uiState)
    val center by shared.center.collectAsState()
    val windows =
        listOf(
            ToolWindow(BRANCHES, graftText("branches.title", "Branches"), GraftIcon.BRANCH, DockSide.LEFT) {
                BranchesDestination(BranchesRoute(folder), session, shared)
            },
            ToolWindow(DETAILS, graftText("details.title", "Details"), GraftIcon.INFORMATION, DockSide.RIGHT) {
                DetailsDestination(DetailsRoute(folder), session, shared)
            },
            // Closed until opened from its stripe icon (Task 16 wireframe).
            ToolWindow(
                OPERATIONS,
                graftText("operations.title", "Operations"),
                GraftIcon.RECENTLY_VIEWED,
                DockSide.BOTTOM,
            ) {
                OperationsDestination(OperationsRoute(folder), session, shared)
            },
            // Every git command the repository ran; closed until opened from its stripe icon or Mod+3 (Task 20).
            ToolWindow(GIT_LOG, graftText("gitlog.title", "Git log"), GraftIcon.LIST, DockSide.BOTTOM) {
                GitLogDestination(GitLogRoute(folder))
            },
        )
    DockLayout(dock, windows) {
        Box(Modifier.fillMaxSize()) {
            GraphDestination(GraphRoute(folder), session, shared)
            // Compare stays composed under a diff opened from it, so going back keeps its lists.
            val compareShown = center is CenterView.Compare || (center as? CenterView.Diff)?.back is CenterView.Compare
            if (compareShown) {
                Box(Modifier.fillMaxSize().background(Camouflage.colors.surface)) {
                    CompareDestination(CompareRoute(folder), session, shared)
                }
            }
            if (center is CenterView.Diff) {
                Box(Modifier.fillMaxSize().background(Camouflage.colors.surface)) {
                    DiffDestination(DiffRoute(folder), shared)
                }
            }
        }
    }
}

/** The layout a repository starts with: branches on the left, details on the right. */
public fun defaultDock(): DockLayoutState = DockLayoutState().apply {
    show(BRANCHES, DockSide.LEFT)
    show(DETAILS, DockSide.RIGHT)
}

/** The ids of the repository screen's tool windows, for the host's panel shortcuts and menus. */
public object PanelIds {
    public const val BRANCHES: String = "branches"
    public const val DETAILS: String = "details"
    public const val OPERATIONS: String = "operations"
    public const val GIT_LOG: String = "gitlog"
}

private const val BRANCHES = PanelIds.BRANCHES
private const val DETAILS = PanelIds.DETAILS
private const val OPERATIONS = PanelIds.OPERATIONS
private const val GIT_LOG = PanelIds.GIT_LOG
