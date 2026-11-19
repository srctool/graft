// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.presentation.onboarding

import com.srctool.graft.data.app.api.models.AppSettings
import com.srctool.graft.data.app.api.models.Density
import com.srctool.graft.data.app.api.models.DiffStyle
import com.srctool.graft.data.app.api.models.ThemeChoice
import com.srctool.graft.data.app.api.repositories.SettingsRepository
import com.srctool.graft.data.git.api.models.GitInstall
import com.srctool.graft.data.git.api.models.GitInstallStatus
import com.srctool.graft.data.git.api.repositories.GitInstallRepository
import com.srctool.graft.data.git.api.repositories.IdentityRepository
import com.srctool.sinew.models.Result
import com.srctool.sinew.viewmodel.EventActionViewModel
import java.nio.file.Path

/** The welcome steps G1 has (Onboarding note: 1 Git, 2 identity, 9 look, 10 repositories). */
internal enum class OnboardingStep { Git, Identity, Look, Repositories }

/** What step 1 found. */
internal sealed interface GitCheck {
    data object Checking : GitCheck

    data class Found(val install: GitInstall) : GitCheck

    data class TooOld(val found: String) : GitCheck

    data object Missing : GitCheck

    /** git started but its answer wasn't understood. */
    data class Failed(val error: Throwable) : GitCheck
}

/** Where a configured value comes from, shown next to its field. */
internal data class IdentityOrigin(val file: Path?)

internal data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.Git,
    val git: GitCheck = GitCheck.Checking,
    val identityLoaded: Boolean = false,
    val name: String = "",
    val email: String = "",
    /** What git reads now; the fields differ from these only when the author edited them. */
    val configuredName: String? = null,
    val configuredEmail: String? = null,
    val nameFile: Path? = null,
    val emailFile: Path? = null,
    val running: Boolean = false,
    val error: Throwable? = null,
    val outputShown: Boolean = false,
    val settings: AppSettings = AppSettings(),
) {
    /** The lines **Set and continue** would run: only the keys whose value changed. Empty: nothing to write. */
    val commands: List<String>
        get() = buildList {
            if (name.trim() != configuredName.orEmpty()) add("git config --global user.name ${quote(name.trim())}")
            if (email.trim() != configuredEmail.orEmpty()) add("git config --global user.email ${quote(email.trim())}")
        }

    val last: Boolean get() = step == OnboardingStep.entries.last()

    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

internal sealed interface OnboardingEvent {
    data object Opened : OnboardingEvent

    data object GitRechecked : OnboardingEvent

    data object Next : OnboardingEvent

    data object Back : OnboardingEvent

    data class NameChanged(val name: String) : OnboardingEvent

    data class EmailChanged(val email: String) : OnboardingEvent

    /** **Set and continue** on the identity step. */
    data object IdentityApplied : OnboardingEvent

    data class ThemeChosen(val theme: ThemeChoice) : OnboardingEvent

    data class DensityChosen(val density: Density) : OnboardingEvent

    data class DiffStyleChosen(val style: DiffStyle) : OnboardingEvent

    data class OutputShown(val shown: Boolean) : OnboardingEvent

    /** **Finish**, or ✕: either way the first run is over. */
    data object Finished : OnboardingEvent
}

internal sealed interface OnboardingAction {
    data object Start : OnboardingAction

    data object CheckGit : OnboardingAction

    class Edit(val change: (OnboardingState) -> OnboardingState) : OnboardingAction

    data object Move : OnboardingAction

    data object Back : OnboardingAction

    data object Apply : OnboardingAction

    class Change(val change: (AppSettings) -> AppSettings) : OnboardingAction

    data object Finish : OnboardingAction
}

internal sealed interface OnboardingEffect {
    /** The first run is recorded; the host closes the dialog. */
    data object Done : OnboardingEffect
}

/**
 * Onboarding (S38): git check, identity (D5: shown, edited, and set only after showing the exact lines, nothing
 * written when unchanged), look (each choice applies at once) and repositories. Every step can be skipped with
 * **Next**; **Finish** and closing both record that the first run happened.
 */
internal class OnboardingViewModel(
    private val install: GitInstallRepository,
    private val identity: IdentityRepository,
    private val settings: SettingsRepository,
) : EventActionViewModel<OnboardingState, OnboardingEvent, OnboardingAction, OnboardingEffect>(OnboardingState()) {
    private var opened = false

    override fun mapEventToAction(event: OnboardingEvent): OnboardingAction? = when (event) {
        OnboardingEvent.Opened -> if (opened) null else OnboardingAction.Start.also { opened = true }
        OnboardingEvent.GitRechecked -> OnboardingAction.CheckGit
        OnboardingEvent.Next -> OnboardingAction.Move
        OnboardingEvent.Back -> OnboardingAction.Back
        is OnboardingEvent.NameChanged -> OnboardingAction.Edit { it.copy(name = event.name, error = null) }
        is OnboardingEvent.EmailChanged -> OnboardingAction.Edit { it.copy(email = event.email, error = null) }
        OnboardingEvent.IdentityApplied -> OnboardingAction.Apply
        is OnboardingEvent.ThemeChosen -> OnboardingAction.Change { it.copy(theme = event.theme) }
        is OnboardingEvent.DensityChosen -> OnboardingAction.Change { it.copy(density = event.density) }
        is OnboardingEvent.DiffStyleChosen -> OnboardingAction.Change { it.copy(diffStyle = event.style) }
        is OnboardingEvent.OutputShown -> OnboardingAction.Edit { it.copy(outputShown = event.shown) }
        OnboardingEvent.Finished -> OnboardingAction.Finish
    }

    override suspend fun onAction(action: OnboardingAction) {
        when (action) {
            OnboardingAction.Start -> {
                (settings.read() as? Result.Success)?.data?.value?.let { s -> setState { copy(settings = s) } }
                checkGit()
                loadIdentity()
            }

            OnboardingAction.CheckGit -> checkGit()

            is OnboardingAction.Edit -> setState(action.change)

            OnboardingAction.Move -> move(1)

            OnboardingAction.Back -> move(-1)

            OnboardingAction.Apply -> apply()

            is OnboardingAction.Change -> change(action.change)

            OnboardingAction.Finish -> finish()
        }
    }

    private fun move(by: Int) {
        val steps = OnboardingStep.entries
        val next = steps.getOrNull(currentState.step.ordinal + by) ?: return
        setState { copy(step = next, error = null, outputShown = false) }
    }

    private suspend fun checkGit() {
        setState { copy(git = GitCheck.Checking) }
        val found =
            when (val status = install.check()) {
                is GitInstallStatus.Ready -> GitCheck.Found(status.install)
                is GitInstallStatus.TooOld -> GitCheck.TooOld(status.found)
                GitInstallStatus.Missing -> GitCheck.Missing
                is GitInstallStatus.Unreadable -> GitCheck.Failed(status.error)
            }
        setState { copy(git = found) }
    }

    private suspend fun loadIdentity() {
        when (val r = identity.read()) {
            is Result.Success -> {
                val id = r.data
                setState {
                    copy(
                        identityLoaded = true,
                        name = id.name?.value.orEmpty(),
                        email = id.email?.value.orEmpty(),
                        configuredName = id.name?.value,
                        configuredEmail = id.email?.value,
                        nameFile = id.name?.file,
                        emailFile = id.email?.file,
                    )
                }
            }

            // Unreadable config: the step still works, starting from empty fields.
            is Result.Failure -> setState { copy(identityLoaded = true, error = r.error) }
        }
    }

    private suspend fun apply() {
        val s = currentState
        if (s.running) return
        // Nothing to write: go on without touching the config.
        if (s.commands.isEmpty()) return move(1)
        setState { copy(running = true, error = null) }
        when (val r = identity.write(s.name.trim(), s.email.trim())) {
            is Result.Success -> {
                val id = r.data.identity
                setState {
                    copy(
                        running = false,
                        configuredName = id.name?.value,
                        configuredEmail = id.email?.value,
                        nameFile = id.name?.file,
                        emailFile = id.email?.file,
                    )
                }
                move(1)
            }

            is Result.Failure -> setState { copy(running = false, error = r.error) }
        }
    }

    private suspend fun change(change: (AppSettings) -> AppSettings) {
        when (val r = settings.update(change)) {
            is Result.Success -> setState { copy(settings = r.data, error = null) }
            is Result.Failure -> setState { copy(error = r.error) }
        }
    }

    private suspend fun finish() {
        // A file from a newer Graft refuses writes: the first run then simply shows again, which costs nothing.
        settings.update { it.copy(onboarded = true) }
        emitEffect(OnboardingEffect.Done)
    }
}
