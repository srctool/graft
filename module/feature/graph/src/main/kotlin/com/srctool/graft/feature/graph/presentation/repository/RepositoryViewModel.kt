// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.repository

import androidx.lifecycle.viewModelScope
import com.srctool.graft.data.app.api.models.PanelLayout
import com.srctool.graft.data.app.api.repositories.RepositoryUiStateRepository
import com.srctool.graft.presentation.view.RepositoryViewState
import com.srctool.sinew.viewmodel.EventActionViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Path

/** [restore] is the saved panel layout for the dock to take; null until the saved choices have been read. */
internal data class RepositoryScreenState(val restore: PanelLayout? = null)

internal sealed interface RepositoryScreenEvent {
    data object Opened : RepositoryScreenEvent

    /** The user moved, opened, closed or resized a panel. */
    data class LayoutChanged(val panels: PanelLayout) : RepositoryScreenEvent
}

internal sealed interface RepositoryScreenEffect

/**
 * One repository tab (the app shell plan, Task 1): it owns the [shared] state its panes read, and saves the panel
 * layout through it, so `state.json` and every pane agree. Events are the actions: nothing needs translating.
 */
internal class RepositoryViewModel(commonDir: Path, store: RepositoryUiStateRepository) :
    EventActionViewModel<RepositoryScreenState, RepositoryScreenEvent, RepositoryScreenEvent, RepositoryScreenEffect>(
        RepositoryScreenState(),
    ) {
    val shared: RepositoryViewState = RepositoryViewState(commonDir, store)

    private var saveJob: Job? = null

    override fun mapEventToAction(event: RepositoryScreenEvent): RepositoryScreenEvent = event

    override suspend fun onAction(action: RepositoryScreenEvent) {
        when (action) {
            RepositoryScreenEvent.Opened -> {
                val saved = shared.load()
                setState { copy(restore = saved.panels) }
            }

            is RepositoryScreenEvent.LayoutChanged -> save(action.panels)
        }
    }

    /** After a pause, so a drag is one write. Nothing is saved before the read, which would be overwritten. */
    private fun save(panels: PanelLayout) {
        if (currentState.restore == null) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_PAUSE_MS)
            shared.update { it.copy(panels = panels) }
        }
    }

    internal companion object {
        const val SAVE_PAUSE_MS: Long = 500
    }
}
