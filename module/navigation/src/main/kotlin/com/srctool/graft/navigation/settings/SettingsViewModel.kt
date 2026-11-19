// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.settings

import com.srctool.graft.data.app.api.models.AppSettings
import com.srctool.graft.data.app.api.models.Density
import com.srctool.graft.data.app.api.models.DiffStyle
import com.srctool.graft.data.app.api.models.SavedStatus
import com.srctool.graft.data.app.api.models.ThemeChoice
import com.srctool.graft.data.app.api.repositories.SettingsRepository
import com.srctool.sinew.models.Result
import com.srctool.sinew.viewmodel.EventActionViewModel

/** The pages G1 has, in the tree's order (Settings Reference). */
internal enum class SettingsPage(val group: SettingsGroup) {
    Appearance(SettingsGroup.General),
    Diffs(SettingsGroup.WorkingCopy),
    Onboarding(SettingsGroup.Onboarding),
}

internal enum class SettingsGroup { General, WorkingCopy, Onboarding }

internal data class SettingsState(
    val loaded: Boolean = false,
    val settings: AppSettings = AppSettings(),
    /** Saved by a newer Graft: shown, never written back. */
    val readOnly: Boolean = false,
    val query: String = "",
    val page: SettingsPage = SettingsPage.Appearance,
    /** The inline size limit as typed, in MB; the saved value only changes when this is a number. */
    val limitText: String = "2",
    val error: Throwable? = null,
) {
    val limitInvalid: Boolean get() = limitText.trim().toLongOrNull()?.let { it < 1 } ?: true
}

internal sealed interface SettingsEvent {
    data object Opened : SettingsEvent

    data class QueryTyped(val query: String) : SettingsEvent

    data class PageChosen(val page: SettingsPage) : SettingsEvent

    data class ThemeChosen(val theme: ThemeChoice) : SettingsEvent

    data class DensityChosen(val density: Density) : SettingsEvent

    data class DiffStyleChosen(val style: DiffStyle) : SettingsEvent

    data object IgnoreWhitespaceToggled : SettingsEvent

    data class LimitTyped(val text: String) : SettingsEvent

    data object WelcomeAgain : SettingsEvent
}

internal sealed interface SettingsAction {
    data object Start : SettingsAction

    class Local(val change: (SettingsState) -> SettingsState) : SettingsAction

    class Write(val change: (AppSettings) -> AppSettings) : SettingsAction

    data class Limit(val text: String) : SettingsAction

    data object Welcome : SettingsAction
}

internal sealed interface SettingsEffect {
    /** **Run the welcome steps again…**: the host closes Settings and Home opens Onboarding. */
    data object WelcomeAgain : SettingsEffect
}

/**
 * Settings (S37). Every change is written as it's made (approved: no OK or Apply), through
 * [SettingsRepository.update], which publishes the result so the window re-themes at once. A file from a newer Graft is
 * shown with its controls off; a failed write leaves the old value and says why.
 */
internal class SettingsViewModel(private val settings: SettingsRepository) :
    EventActionViewModel<SettingsState, SettingsEvent, SettingsAction, SettingsEffect>(SettingsState()) {
    private var opened = false

    override fun mapEventToAction(event: SettingsEvent): SettingsAction? = when (event) {
        SettingsEvent.Opened -> if (opened) null else SettingsAction.Start.also { opened = true }

        is SettingsEvent.QueryTyped -> SettingsAction.Local { it.copy(query = event.query) }

        is SettingsEvent.PageChosen -> SettingsAction.Local { it.copy(page = event.page) }

        is SettingsEvent.ThemeChosen -> SettingsAction.Write { it.copy(theme = event.theme) }

        is SettingsEvent.DensityChosen -> SettingsAction.Write { it.copy(density = event.density) }

        is SettingsEvent.DiffStyleChosen -> SettingsAction.Write { it.copy(diffStyle = event.style) }

        SettingsEvent.IgnoreWhitespaceToggled -> SettingsAction.Write {
            it.copy(ignoreWhitespace = !it.ignoreWhitespace)
        }

        is SettingsEvent.LimitTyped -> SettingsAction.Limit(event.text)

        SettingsEvent.WelcomeAgain -> SettingsAction.Welcome
    }

    override suspend fun onAction(action: SettingsAction) {
        when (action) {
            SettingsAction.Start -> start()
            is SettingsAction.Local -> setState(action.change)
            is SettingsAction.Write -> write(action.change)
            is SettingsAction.Limit -> limit(action.text)
            SettingsAction.Welcome -> emitEffect(SettingsEffect.WelcomeAgain)
        }
    }

    private suspend fun start() {
        when (val r = settings.read()) {
            is Result.Success -> {
                val saved = r.data
                setState {
                    copy(
                        loaded = true,
                        settings = saved.value,
                        readOnly = saved.status is SavedStatus.Newer,
                        limitText = megabytes(saved.value),
                    )
                }
            }

            is Result.Failure -> setState { copy(loaded = true, error = r.error, readOnly = true) }
        }
    }

    private suspend fun write(change: (AppSettings) -> AppSettings) {
        if (currentState.readOnly) return
        when (val r = settings.update(change)) {
            is Result.Success -> setState { copy(settings = r.data, error = null, limitText = megabytes(r.data)) }
            is Result.Failure -> setState { copy(error = r.error) }
        }
    }

    private suspend fun limit(text: String) {
        setState { copy(limitText = text) }
        val megabytes = text.trim().toLongOrNull()?.takeIf { it >= 1 } ?: return
        if (currentState.readOnly) return
        when (val r = settings.update { it.copy(inlineDiffLimitBytes = megabytes * MB) }) {
            is Result.Success -> setState { copy(settings = r.data, error = null) }
            is Result.Failure -> setState { copy(error = r.error) }
        }
    }

    private fun megabytes(s: AppSettings): String = (s.inlineDiffLimitBytes / MB).coerceAtLeast(1).toString()

    private companion object {
        const val MB = 1024L * 1024
    }
}
