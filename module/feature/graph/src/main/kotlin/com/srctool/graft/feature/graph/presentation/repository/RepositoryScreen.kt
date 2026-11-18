// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import com.srctool.graft.data.app.api.repositories.SettingsRepository
import com.srctool.graft.data.git.api.repositories.CommitRepository
import com.srctool.graft.data.git.api.repositories.CompareRepository
import com.srctool.graft.data.git.api.repositories.DiffRepository
import com.srctool.graft.data.git.api.repositories.StashRepository
import com.srctool.graft.feature.graph.presentation.branches.BranchesDestination
import com.srctool.graft.feature.graph.presentation.branches.BranchesRoute
import com.srctool.graft.feature.graph.presentation.compare.CompareDestination
import com.srctool.graft.feature.graph.presentation.compare.CompareRoute
import com.srctool.graft.feature.graph.presentation.details.DetailsDestination
import com.srctool.graft.feature.graph.presentation.details.DetailsRoute
import com.srctool.graft.feature.graph.presentation.diff.DiffDestination
import com.srctool.graft.feature.graph.presentation.diff.DiffRoute
import com.srctool.graft.feature.graph.presentation.graph.GraphDestination
import com.srctool.graft.feature.graph.presentation.graph.GraphRoute
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.view.CenterView
import com.srctool.graft.presentation.view.RepositoryViewState

/** What one repository tab's screens need from the data layer. */
public class RepositoryData(
    public val commits: CommitRepository,
    public val compare: CompareRepository,
    public val stashes: StashRepository,
    public val diffs: DiffRepository,
    public val uiState: RepositoryUiStateRepository,
    public val settings: SettingsRepository,
)

/**
 * Temporary (G1 Task 14, question 4): one repository's left panel, graph or diff, and commit details in a
 * [DockLayout], sharing a [RepositoryViewState]. Task 20's main window host in :module:navigation replaces it and
 * keeps the screens. The graph stays composed under a diff, so going back keeps its selection and scroll.
 */
@Composable
public fun RepositoryScreen(session: RepositorySession, data: RepositoryData) {
    val location = session.location
    val folder = (location.workTree ?: location.gitDir).toString()
    val shared = remember(location) { RepositoryViewState(location.commonDir, data.uiState) }
    val dock =
        remember(location) {
            DockLayoutState().apply {
                show(BRANCHES, DockSide.LEFT)
                show(DETAILS, DockSide.RIGHT)
            }
        }
    val center by shared.center.collectAsState()
    val windows =
        listOf(
            ToolWindow(BRANCHES, graftText("branches.title", "Branches"), GraftIcon.BRANCH, DockSide.LEFT) {
                BranchesDestination(BranchesRoute(folder), session, shared, data.stashes)
            },
            ToolWindow(DETAILS, graftText("details.title", "Details"), GraftIcon.INFORMATION, DockSide.RIGHT) {
                DetailsDestination(DetailsRoute(folder), session, shared, data.commits)
            },
        )
    DockLayout(dock, windows) {
        Box(Modifier.fillMaxSize()) {
            GraphDestination(GraphRoute(folder), session, data.commits, data.stashes, shared)
            // Compare stays composed under a diff opened from it, so going back keeps its lists.
            val compareShown = center is CenterView.Compare || (center as? CenterView.Diff)?.back is CenterView.Compare
            if (compareShown) {
                Box(Modifier.fillMaxSize().background(Camouflage.colors.surface)) {
                    CompareDestination(CompareRoute(folder), session, shared, data.compare, data.commits, data.diffs)
                }
            }
            if (center is CenterView.Diff) {
                Box(Modifier.fillMaxSize().background(Camouflage.colors.surface)) {
                    DiffDestination(DiffRoute(folder), shared, data.diffs, data.settings)
                }
            }
        }
    }
}

private const val BRANCHES = "branches"
private const val DETAILS = "details"
