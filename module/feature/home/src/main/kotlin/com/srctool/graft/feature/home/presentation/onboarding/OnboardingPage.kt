// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.presentation.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.button.CamoButton
import com.srctool.camouflage.core.dialog.CamoDialog
import com.srctool.camouflage.core.dialog.CamoDialogFrame
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.text.CamoText
import com.srctool.camouflage.core.textfield.CamoTextField
import com.srctool.camouflage.core.theme.Appearance
import com.srctool.graft.core.exception.CommandOutput
import com.srctool.graft.core.ui.components.ChoiceRow
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.data.app.api.models.Density
import com.srctool.graft.data.app.api.models.DiffStyle
import com.srctool.graft.data.app.api.models.ThemeChoice
import com.srctool.graft.feature.home.presentation.rememberDestinationOwner
import com.srctool.sinew.exception.displayMessage
import com.srctool.sinew.models.SinewException
import com.srctool.sinew.viewmodel.ObserveEffects
import org.koin.compose.viewmodel.koinViewModel

/**
 * Onboarding (S38) over Home. [onOpenClone], [onOpenFolder] and [onScan] belong to Home's own dialogs; the first run
 * is recorded before any of them opens, so choosing one doesn't bring the welcome back.
 */
@Composable
internal fun OnboardingDialog(
    onClose: () -> Unit,
    onOpenClone: () -> Unit,
    onOpenFolder: () -> Unit,
    onScan: () -> Unit,
) {
    val viewModel = koinViewModel<OnboardingViewModel>(viewModelStoreOwner = rememberDestinationOwner("onboarding"))
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) { viewModel.onEvent(OnboardingEvent.Opened) }
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            OnboardingEffect.Done -> onClose()
        }
    }
    val finish = { viewModel.onEvent(OnboardingEvent.Finished) }
    CamoDialogFrame(onDismissRequest = finish) {
        val output = state.error as? CommandOutput
        if (state.outputShown && output != null) {
            CamoDialog(
                message = output.argv.joinToString(" "),
                title = graftText("graph.output.title", "Git output"),
                confirm = {
                    CamoButton(graftText("graph.output.close", "Close"), onClick = {
                        viewModel.onEvent(OnboardingEvent.OutputShown(false))
                    })
                },
            ) {
                BasicText(output.stderr, style = Graft.tokens.code.copy(color = Camouflage.colors.onSurface))
            }
        } else {
            OnboardingContent(state, viewModel::onEvent, finish, onOpenClone, onOpenFolder, onScan)
        }
    }
}

/** The dialog without its view model, for screenshot tests. */
@Composable
internal fun OnboardingContent(
    state: OnboardingState,
    onEvent: (OnboardingEvent) -> Unit,
    onClose: () -> Unit,
    onOpenClone: () -> Unit = {},
    onOpenFolder: () -> Unit = {},
    onScan: () -> Unit = {},
) {
    val steps = OnboardingStep.entries
    val position = state.step.ordinal + 1
    val stepName = stepName(state.step)
    val identityChanges = state.step == OnboardingStep.Identity && state.commands.isNotEmpty()
    val spoken =
        graftText(
            "onboarding.label",
            "Step {n} of {total}, {name}",
            "n" to position,
            "total" to steps.size,
            "name" to stepName,
        )
    CamoDialog(
        message = graftText("onboarding.step", "Step {n} of {total}", "n" to position, "total" to steps.size),
        title = graftText("onboarding.title", "Welcome to Graft"),
        modifier = Modifier.width(560.dp).semantics { contentDescription = spoken },
        confirm = {
            when {
                state.last -> CamoButton(graftText("onboarding.finish", "Finish"), onClick = onClose)

                identityChanges -> CamoButton(
                    graftText("onboarding.setAndContinue", "Set and continue"),
                    onClick = { onEvent(OnboardingEvent.IdentityApplied) },
                    loading = state.running,
                )

                else -> CamoButton(
                    graftText("onboarding.next", "Next"),
                    onClick = { onEvent(OnboardingEvent.Next) },
                    enabled = !state.running,
                )
            }
        },
        dismiss = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.step != steps.first()) {
                    CamoButton(
                        graftText("onboarding.back", "Back"),
                        onClick = { onEvent(OnboardingEvent.Back) },
                        appearance = Appearance.Ghost,
                        enabled = !state.running,
                    )
                }
                if (!state.last &&
                    (identityChanges || (state.step == OnboardingStep.Git && state.git !is GitCheck.Found))
                ) {
                    CamoButton(
                        graftText("onboarding.skip", "Skip this step"),
                        onClick = { onEvent(OnboardingEvent.Next) },
                        appearance = Appearance.Ghost,
                        enabled = !state.running,
                    )
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CamoText(stepName, style = Graft.tokens.denseStrong)
            when (state.step) {
                OnboardingStep.Git -> GitStep(state, onEvent)
                OnboardingStep.Identity -> IdentityStep(state, onEvent)
                OnboardingStep.Look -> LookStep(state, onEvent)
                OnboardingStep.Repositories -> RepositoriesStep(onOpenClone, onOpenFolder, onScan)
            }
        }
    }
}

@Composable
private fun stepName(step: OnboardingStep): String = when (step) {
    OnboardingStep.Git -> graftText("onboarding.git", "Git")
    OnboardingStep.Identity -> graftText("onboarding.identity", "Your name and email")
    OnboardingStep.Look -> graftText("onboarding.look", "Look")
    OnboardingStep.Repositories -> graftText("onboarding.repositories", "Repositories")
}

@Composable
private fun GitStep(state: OnboardingState, onEvent: (OnboardingEvent) -> Unit) {
    val colors = Camouflage.colors
    when (val git = state.git) {
        GitCheck.Checking -> CamoText(
            graftText("onboarding.git.checking", "Checking git…"),
            style = Graft.tokens.dense,
            color = colors.onSurfaceVariant,
        )

        is GitCheck.Found -> {
            val location = git.install.location
            CamoText(
                if (location != null) {
                    graftText(
                        "onboarding.git.foundAt",
                        "Git {version} found at {path}",
                        "version" to git.install.version,
                        "path" to location,
                    )
                } else {
                    graftText("onboarding.git.found", "Git {version} found", "version" to git.install.version)
                },
                style = Graft.tokens.dense,
            )
        }

        is GitCheck.TooOld -> Problem(
            graftText(
                "onboarding.git.old",
                "Git {found} is too old. Graft needs 2.38 or newer.",
                "found" to git.found,
            ),
            onEvent,
        )

        GitCheck.Missing -> Problem(
            graftText("onboarding.git.missing", "Git isn't installed, or Graft can't start it."),
            onEvent,
        )

        is GitCheck.Failed -> Problem(
            (git.error as? SinewException)?.displayMessage(Graft.localizer) ?: git.error.message.orEmpty(),
            onEvent,
            fix = false,
        )
    }
}

@Composable
private fun Problem(message: String, onEvent: (OnboardingEvent) -> Unit, fix: Boolean = true) {
    val clipboard = LocalClipboardManager.current
    CamoText(message, style = Graft.tokens.dense, color = Camouflage.colors.error)
    if (fix) {
        for (line in installHints()) {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CamoText(
                    line.first,
                    style = Graft.tokens.dense,
                    modifier = Modifier.width(72.dp),
                    color = Camouflage.colors.onSurfaceVariant,
                )
                BasicText(
                    line.second,
                    style = Graft.tokens.code.copy(color = Camouflage.colors.onSurface),
                    modifier = Modifier.weight(1f),
                )
                CamoButton(
                    graftText("onboarding.copy", "Copy"),
                    onClick = { clipboard.setText(AnnotatedString(line.second)) },
                    modifier = Modifier.height(Graft.tokens.density.control),
                    appearance = Appearance.Ghost,
                )
            }
        }
    }
    CamoButton(
        graftText("onboarding.git.recheck", "Check again"),
        onClick = { onEvent(OnboardingEvent.GitRechecked) },
        modifier = Modifier.height(Graft.tokens.density.control),
        appearance = Appearance.Outline,
    )
}

/** What to run to install git: the command for this OS (Onboarding note, step 1). */
internal fun installHints(os: String = System.getProperty("os.name").orEmpty()): List<Pair<String, String>> = when {
    os.startsWith("Mac", ignoreCase = true) -> listOf("macOS" to "xcode-select --install")

    os.startsWith("Windows", ignoreCase = true) -> listOf("Windows" to "winget install --id Git.Git -e")

    else -> listOf(
        "apt" to "sudo apt install git",
        "dnf" to "sudo dnf install git",
        "pacman" to "sudo pacman -S git",
    )
}

@Composable
private fun IdentityStep(state: OnboardingState, onEvent: (OnboardingEvent) -> Unit) {
    CamoText(
        graftText("onboarding.identity.message", "Graft reads what git has now. Commits use these."),
        style = Graft.tokens.dense,
        color = Camouflage.colors.onSurfaceVariant,
    )
    FieldWithOrigin(
        graftText("onboarding.identity.name", "Name"),
        state.name,
        state.nameFile?.toString(),
        enabled = !state.running,
    ) { onEvent(OnboardingEvent.NameChanged(it)) }
    FieldWithOrigin(
        graftText("onboarding.identity.email", "Email"),
        state.email,
        state.emailFile?.toString(),
        enabled = !state.running,
    ) { onEvent(OnboardingEvent.EmailChanged(it)) }
    if (state.commands.isEmpty()) {
        CamoText(
            graftText("onboarding.identity.nothing", "Nothing to write."),
            style = Graft.tokens.dense,
            color = Camouflage.colors.onSurfaceVariant,
        )
    } else {
        CamoText(graftText("onboarding.identity.willRun", "Graft will run:"), style = Graft.tokens.dense)
        Column(
            Modifier.semantics(mergeDescendants = true) {
                contentDescription = state.commands.joinToString(". ")
            },
        ) {
            for (line in state.commands) {
                BasicText(line, style = Graft.tokens.code.copy(color = Camouflage.colors.onSurface))
            }
        }
    }
    state.error?.let { error ->
        val message = (error as? SinewException)?.displayMessage(Graft.localizer) ?: error.message.orEmpty()
        CamoText(message, style = Graft.tokens.dense, color = Camouflage.colors.error)
        if (error is CommandOutput) {
            CamoButton(
                graftText("graph.showOutput", "Show output"),
                onClick = { onEvent(OnboardingEvent.OutputShown(true)) },
                modifier = Modifier.height(Graft.tokens.density.control),
                appearance = Appearance.Ghost,
            )
        }
    }
}

@Composable
private fun FieldWithOrigin(
    label: String,
    value: String,
    origin: String?,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
        CamoTextField(value, onChange, modifier = Modifier.weight(1f), label = label, enabled = enabled)
        origin?.let {
            CamoText(
                graftText("onboarding.identity.from", "from {file}", "file" to it),
                style = Graft.tokens.dense,
                color = Camouflage.colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun LookStep(state: OnboardingState, onEvent: (OnboardingEvent) -> Unit) {
    CamoText(
        graftText("onboarding.look.message", "Each choice applies as soon as you make it."),
        style = Graft.tokens.dense,
        color = Camouflage.colors.onSurfaceVariant,
    )
    ChoiceRow(
        graftText("settings.theme", "Theme"),
        themeOptions(),
        state.settings.theme,
        { onEvent(OnboardingEvent.ThemeChosen(it)) },
    )
    ChoiceRow(
        graftText("settings.density", "Density"),
        densityOptions(),
        state.settings.density,
        { onEvent(OnboardingEvent.DensityChosen(it)) },
    )
    ChoiceRow(
        graftText("settings.diffStyle", "Diff style"),
        diffStyleOptions(),
        state.settings.diffStyle,
        { onEvent(OnboardingEvent.DiffStyleChosen(it)) },
    )
    state.error?.let {
        CamoText(
            (it as? SinewException)?.displayMessage(Graft.localizer) ?: it.message.orEmpty(),
            style = Graft.tokens.dense,
            color = Camouflage.colors.error,
        )
    }
}

@Composable
internal fun themeOptions(): List<Pair<ThemeChoice, String>> = listOf(
    ThemeChoice.SYSTEM to graftText("settings.theme.system", "System"),
    ThemeChoice.LIGHT to graftText("settings.theme.light", "Light"),
    ThemeChoice.DARK to graftText("settings.theme.dark", "Dark"),
)

@Composable
internal fun densityOptions(): List<Pair<Density, String>> = listOf(
    Density.STANDARD to graftText("settings.density.standard", "Standard"),
    Density.COMFORTABLE to graftText("settings.density.comfortable", "Comfortable"),
)

@Composable
internal fun diffStyleOptions(): List<Pair<DiffStyle, String>> = listOf(
    DiffStyle.UNIFIED to graftText("settings.diffStyle.unified", "Unified"),
    DiffStyle.SIDE_BY_SIDE to graftText("settings.diffStyle.sideBySide", "Side by side"),
)

@Composable
private fun RepositoriesStep(onOpenClone: () -> Unit, onOpenFolder: () -> Unit, onScan: () -> Unit) {
    CamoText(
        graftText("onboarding.repositories.message", "Add your first repositories. You can always do this from Home."),
        style = Graft.tokens.dense,
        color = Camouflage.colors.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        CamoButton(
            graftText("home.open", "Open…"),
            onClick = onOpenFolder,
            modifier = Modifier.height(Graft.tokens.density.control),
            appearance = Appearance.Outline,
        )
        CamoButton(
            graftText("home.clone", "Clone…"),
            onClick = onOpenClone,
            modifier = Modifier.height(Graft.tokens.density.control),
            appearance = Appearance.Outline,
        )
        CamoButton(
            graftText("home.scan", "Scan a folder…"),
            onClick = onScan,
            modifier = Modifier.height(Graft.tokens.density.control),
            appearance = Appearance.Outline,
        )
    }
}
