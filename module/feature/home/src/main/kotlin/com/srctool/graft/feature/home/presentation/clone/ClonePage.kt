// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.presentation.clone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.button.CamoButton
import com.srctool.camouflage.core.checkbox.CamoCheckbox
import com.srctool.camouflage.core.dialog.CamoDialog
import com.srctool.camouflage.core.dialog.CamoDialogFrame
import com.srctool.camouflage.core.progress.CamoProgressBar
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.text.CamoText
import com.srctool.camouflage.core.textfield.CamoTextField
import com.srctool.camouflage.core.theme.Appearance
import com.srctool.camouflage.core.theme.Tone
import com.srctool.graft.core.exception.CommandOutput
import com.srctool.graft.core.ui.components.LocalFolderPicker
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.feature.home.presentation.rememberDestinationOwner
import com.srctool.sinew.exception.displayMessage
import com.srctool.sinew.models.SinewException
import com.srctool.sinew.viewmodel.ObserveEffects
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.nio.file.Path
import java.nio.file.Paths
import java.text.NumberFormat

/**
 * The Clone dialog (S2). [folder] is a folder Home opened that wasn't a repository, cloned into as it is. [onCloned] gets
 * the new repository's root; [onClose] closes the dialog (the user's Cancel, or Esc once nothing is running).
 */
@Composable
internal fun CloneDialog(folder: Path?, onCloned: (Path) -> Unit, onClose: () -> Unit) {
    val viewModel =
        koinViewModel<CloneViewModel>(viewModelStoreOwner = rememberDestinationOwner("clone")) { parametersOf(folder) }
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) { viewModel.onEvent(CloneEvent.Opened) }
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is CloneEffect.Cloned -> onCloned(effect.root)
        }
    }
    var confirmStop by remember { mutableStateOf(false) }
    // Esc and a click outside: while git runs, ask first; otherwise close.
    val dismiss = { if (state.running) confirmStop = true else onClose() }
    CamoDialogFrame(onDismissRequest = dismiss) {
        if (confirmStop) {
            CamoDialog(
                message = graftText("clone.stop.message", "The clone stops and the folder it made is removed."),
                title = graftText("clone.stop.title", "Stop cloning?"),
                confirm = {
                    CamoButton(graftText("clone.stop.confirm", "Stop cloning"), onClick = {
                        viewModel.onEvent(CloneEvent.Cancel)
                        confirmStop = false
                        onClose()
                    }, tone = Tone.Error)
                },
                dismiss = {
                    CamoButton(graftText("clone.stop.keep", "Keep cloning"), onClick = {
                        confirmStop = false
                    }, appearance = Appearance.Ghost)
                },
            )
        } else if (state.outputShown && state.error is CommandOutput) {
            val output = state.error as CommandOutput
            val close = { viewModel.onEvent(CloneEvent.OutputShown(false)) }
            CamoDialog(
                message = output.argv.joinToString(" "),
                title = graftText("graph.output.title", "Git output"),
                confirm = { CamoButton(graftText("graph.output.close", "Close"), onClick = close) },
            ) {
                BasicText(output.stderr, style = Graft.tokens.code.copy(color = Camouflage.colors.onSurface))
            }
        } else {
            CloneDialogContent(state, viewModel::onEvent, dismiss)
        }
    }
}

/** The dialog without its view model or frame, for screenshot tests. */
@Composable
internal fun CloneDialogContent(state: CloneState, onEvent: (CloneEvent) -> Unit, onClose: () -> Unit) {
    val picker = LocalFolderPicker.current
    val urlFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { urlFocus.requestFocus() } }
    val chooseTitle = graftText("clone.browse.title", "Choose a folder for the repository")
    CamoDialog(
        message = graftText("clone.message", "Copy a repository from its URL into a new folder."),
        title = graftText("clone.title", "Clone a repository"),
        modifier = Modifier.width(520.dp),
        confirm = {
            CamoButton(
                graftText("clone.confirm", "Clone"),
                onClick = { onEvent(CloneEvent.Submit) },
                enabled = state.canClone,
                loading = state.running,
            )
        },
        dismiss = {
            CamoButton(
                graftText("clone.cancel", "Cancel"),
                onClick = { if (state.running) onEvent(CloneEvent.Cancel) else onClose() },
                appearance = Appearance.Ghost,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val submit = KeyboardActionHandler { onEvent(CloneEvent.Submit) }
            CamoTextField(
                state.url,
                { onEvent(CloneEvent.UrlChanged(it)) },
                modifier = Modifier.fillMaxWidth().focusRequester(urlFocus),
                label = graftText("clone.url", "URL"),
                placeholder = "https://github.com/acme/app.git",
                error = state.issue?.takeIf { it == CloneIssue.UrlLooksLikeOption }?.let { issueText(it) },
                enabled = !state.running,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                onKeyboardAction = submit,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CamoTextField(
                    state.folder,
                    { onEvent(CloneEvent.FolderChanged(it)) },
                    modifier = Modifier.weight(1f),
                    label = graftText("clone.folder", "Folder"),
                    error = state.issue?.takeIf {
                        it == CloneIssue.FolderNotEmpty || it == CloneIssue.FolderIsAFile
                    }?.let { issueText(it) },
                    enabled = !state.running,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    onKeyboardAction = submit,
                )
                CamoButton(
                    graftText("clone.browse", "Browse…"),
                    onClick = {
                        picker.pick(
                            chooseTitle,
                            state.folder.takeIf {
                                it.isNotBlank()
                            }?.let {
                                runCatching { Paths.get(it).parent }.getOrNull()
                            },
                        )?.let { onEvent(CloneEvent.ParentPicked(it)) }
                    },
                    modifier = Modifier.padding(top = 20.dp).height(Graft.tokens.density.formControl),
                    appearance = Appearance.Outline,
                    enabled = !state.running,
                )
            }
            Options(state, onEvent)
            Progress(state)
            state.error?.let { Failure(it, onEvent) }
        }
    }
}

@Composable
private fun ColumnScope.Options(state: CloneState, onEvent: (CloneEvent) -> Unit) {
    CamoButton(
        (if (state.optionsOpen) "▾ " else "▸ ") + graftText("clone.options", "Options"),
        onClick = { onEvent(CloneEvent.OptionsToggled) },
        modifier = Modifier.height(Graft.tokens.density.control),
        appearance = Appearance.Ghost,
        enabled = !state.running,
    )
    if (!state.optionsOpen) return
    val off = state.running
    Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CamoCheckbox(state.shallow, {
            onEvent(CloneEvent.ShallowToggled)
        }, label = graftText("clone.shallow", "Shallow clone, only the latest commits"), enabled = !off)
        // An option that doesn't apply is hidden, not disabled.
        if (state.shallow) {
            CamoTextField(
                state.depth,
                { onEvent(CloneEvent.DepthChanged(it)) },
                modifier = Modifier.width(220.dp),
                label = graftText("clone.depth", "Commits to copy"),
                error = state.issue?.takeIf { it == CloneIssue.BadDepth }?.let { issueText(it) },
                enabled = !off,
            )
        }
        CamoCheckbox(state.singleBranch, {
            onEvent(CloneEvent.SingleBranchToggled)
        }, label = graftText("clone.single", "Only one branch"), enabled = !off)
        if (state.singleBranch) {
            CamoTextField(
                state.branch,
                { onEvent(CloneEvent.BranchChanged(it)) },
                modifier = Modifier.width(220.dp),
                label = graftText("clone.branch", "Branch (blank for the default)"),
                enabled = !off,
            )
        }
        CamoCheckbox(state.submodules, {
            onEvent(CloneEvent.SubmodulesToggled)
        }, label = graftText("clone.submodules", "Include submodules"), enabled = !off)
        CamoCheckbox(state.partial, {
            onEvent(CloneEvent.PartialToggled)
        }, label = graftText("clone.partial", "Partial clone (download file contents when needed)"), enabled = !off)
    }
}

@Composable
private fun Progress(state: CloneState) {
    if (!state.running) return
    val phase = state.phase ?: graftText("clone.starting", "Starting git…")
    val count =
        if (state.done != null && state.total != null) {
            val f = NumberFormat.getIntegerInstance()
            " (" +
                graftText(
                    "clone.count",
                    "{done} of {total}",
                    "done" to f.format(state.done),
                    "total" to f.format(state.total),
                ) +
                ")"
        } else {
            ""
        }
    val spoken =
        state.percent?.let {
            graftText("clone.spoken", "{phase}, {percent} percent", "phase" to phase, "percent" to it)
        }
            ?: phase
    Column(
        Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = spoken
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CamoText(
            phase + (
                state.percent?.let {
                    " $it%"
                } ?: ""
                ) + count,
            style = Graft.tokens.dense,
            color = Camouflage.colors.onSurfaceVariant,
        )
        CamoProgressBar(progress = state.percent?.let { it / 100f })
    }
}

@Composable
private fun Failure(error: Throwable, onEvent: (CloneEvent) -> Unit) {
    val message = (error as? SinewException)?.displayMessage(Graft.localizer) ?: error.message.orEmpty()
    val code = (error as? SinewException)?.code
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CamoText(
            if (code != null && code !in message) "$message · $code" else message,
            style = Graft.tokens.dense,
            color = Camouflage.colors.error,
        )
        if (error is CommandOutput) {
            CamoButton(
                graftText("graph.showOutput", "Show output"),
                onClick = { onEvent(CloneEvent.OutputShown(true)) },
                modifier = Modifier.height(Graft.tokens.density.control),
                appearance = Appearance.Ghost,
            )
        }
    }
}

@Composable
private fun issueText(issue: CloneIssue): String = when (issue) {
    CloneIssue.NoUrl -> ""

    CloneIssue.UrlLooksLikeOption -> graftText("clone.issue.option", "A URL can't start with a dash.")

    CloneIssue.NoFolder -> graftText("clone.issue.noFolder", "Choose a folder.")

    CloneIssue.FolderNotEmpty -> graftText(
        "clone.issue.notEmpty",
        "This folder isn't empty. Choose another or an empty one.",
    )

    CloneIssue.FolderIsAFile -> graftText("clone.issue.file", "This is a file, not a folder.")

    CloneIssue.BadDepth -> graftText("clone.issue.depth", "Enter a number of commits, such as 50.")
}
