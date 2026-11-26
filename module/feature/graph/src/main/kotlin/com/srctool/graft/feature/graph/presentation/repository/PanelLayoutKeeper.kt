// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.repository

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.srctool.graft.core.ui.components.DockLayoutSnapshot
import com.srctool.graft.core.ui.components.DockLayoutState
import com.srctool.graft.core.ui.components.DockSide
import com.srctool.graft.data.app.api.models.PanelLayout
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/**
 * Applies [restore] to [dock] once it is there, then reports every change the user makes through [onChange]. A
 * repository with no saved layout keeps what [dock] started with.
 */
@Composable
internal fun PanelLayoutKeeper(dock: DockLayoutState, restore: PanelLayout?, onChange: (PanelLayout) -> Unit) {
    val change by rememberUpdatedState(onChange)
    LaunchedEffect(dock, restore) {
        if (restore == null) return@LaunchedEffect
        if (restore.saved) dock.restore(restore.toSnapshot())
        snapshotFlow { dock.snapshot() }
            .distinctUntilChanged()
            .drop(1)
            .collect { change(it.toLayout()) }
    }
}

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
