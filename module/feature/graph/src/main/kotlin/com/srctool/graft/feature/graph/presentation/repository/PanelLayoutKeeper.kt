// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.repository

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.srctool.graft.core.ui.components.DockLayoutSnapshot
import com.srctool.graft.core.ui.components.DockLayoutState
import com.srctool.graft.core.ui.components.DockSide
import com.srctool.graft.data.app.api.models.PanelLayout
import com.srctool.graft.data.app.api.repositories.RepositoryUiStateRepository
import com.srctool.sinew.models.Result
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import java.nio.file.Path

/**
 * Applies the layout saved for the repository at [commonDir] to [dock] once, then saves every change the user makes
 * (after a pause, so a drag is one write). A repository with no saved layout keeps what [dock] started with.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@Composable
internal fun PanelLayoutKeeper(dock: DockLayoutState, commonDir: Path, uiState: RepositoryUiStateRepository) {
    LaunchedEffect(dock, commonDir) {
        val saved = (uiState.read(commonDir) as? Result.Success)?.data?.value?.panels
        if (saved != null && saved.saved) dock.restore(saved.toSnapshot())
        snapshotFlow { dock.snapshot() }
            .distinctUntilChanged()
            .drop(1)
            .debounce(SAVE_PAUSE_MS)
            .collect { snapshot -> uiState.update(commonDir) { it.copy(panels = snapshot.toLayout()) } }
    }
}

private const val SAVE_PAUSE_MS = 500L

internal fun DockLayoutSnapshot.toLayout() = PanelLayout(
    sides = sides.mapValues { it.value.dockName() },
    open = open.mapKeys { it.key.dockName() },
    sizes = sizes.mapKeys { it.key.dockName() },
    saved = true,
)

internal fun PanelLayout.toSnapshot() = DockLayoutSnapshot(
    sides = sides.mapNotNull { (id, side) -> side.toDock()?.let { id to it } }.toMap(),
    open = open.mapNotNull { (side, id) -> side.toDock()?.let { it to id } }.toMap(),
    sizes = sizes.mapNotNull { (side, size) -> side.toDock()?.let { it to size } }.toMap(),
)

private fun DockSide.dockName() = name.lowercase()

private fun String.toDock(): DockSide? = DockSide.entries.firstOrNull { it.dockName() == this }
