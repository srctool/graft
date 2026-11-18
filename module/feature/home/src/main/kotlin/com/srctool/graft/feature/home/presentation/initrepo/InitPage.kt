// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.presentation.initrepo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.button.CamoButton
import com.srctool.camouflage.core.checkbox.CamoCheckbox
import com.srctool.camouflage.core.dialog.CamoDialog
import com.srctool.camouflage.core.dialog.CamoDialogFrame
import com.srctool.camouflage.core.menu.CamoMenuAnchor
import com.srctool.camouflage.core.menu.CamoMenuGroup
import com.srctool.camouflage.core.menu.CamoMenuItem
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.text.CamoText
import com.srctool.camouflage.core.textfield.CamoTextField
import com.srctool.camouflage.core.theme.Appearance
import com.srctool.graft.core.exception.CommandOutput
import com.srctool.graft.core.ui.components.LocalFolderPicker
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.feature.home.domain.api.models.InitOutcome
import com.srctool.graft.feature.home.domain.api.models.InitStep
import com.srctool.graft.feature.home.presentation.rememberDestinationOwner
import com.srctool.sinew.exception.displayMessage
import com.srctool.sinew.models.SinewException
import com.srctool.sinew.viewmodel.ObserveEffects
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The Init dialog (S3). [folder] is a folder Home opened that wasn't a repository. [onCreated] gets the new
 * repository's root, [onOpenExisting] a folder that turned out to be a repository already, [onClose] closes the dialog.
 */
@Composable
internal fun InitDialog(folder: Path?, onCreated: (Path) -> Unit, onOpenExisting: (Path) -> Unit, onClose: () -> Unit) {
    val viewModel =
        koinViewModel<InitViewModel>(viewModelStoreOwner = rememberDestinationOwner("init")) { parametersOf(folder) }
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) { viewModel.onEvent(InitEvent.Opened) }
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is InitEffect.Created -> onCreated(effect.root)
            is InitEffect.OpenExisting -> onOpenExisting(effect.folder)
        }
    }
    CamoDialogFrame(onDismissRequest = { if (!state.running) onClose() }) {
        if (state.outputShown && state.error is CommandOutput) {
            val output = state.error as CommandOutput
            val close = { viewModel.onEvent(InitEvent.OutputShown(false)) }
            CamoDialog(
                message = output.argv.joinToString(" "),
                title = graftText("graph.output.title", "Git output"),
                confirm = { CamoButton(graftText("graph.output.close", "Close"), onClick = close) },
            ) {
                BasicText(output.stderr, style = Graft.tokens.code.copy(color = Camouflage.colors.onSurface))
            }
        } else {
            InitDialogContent(state, viewModel::onEvent, onClose)
        }
    }
}

/** The dialog without its view model or frame, for screenshot tests. */
@Composable
internal fun InitDialogContent(state: InitState, onEvent: (InitEvent) -> Unit, onClose: () -> Unit) {
    val picker = LocalFolderPicker.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val chooseTitle = graftText("init.browse.title", "Choose the repository's folder")
    val submit = KeyboardActionHandler { onEvent(InitEvent.Submit) }
    val partial = state.partial
    CamoDialog(
        message = graftText("init.message", "Start a new repository in a folder."),
        title = graftText("init.title", "Create a repository"),
        modifier = Modifier.width(520.dp),
        confirm = {
            if (partial != null) {
                CamoButton(graftText("init.openIt", "Open the repository"), onClick = {
                    onEvent(InitEvent.OpenPartial)
                })
            } else {
                CamoButton(
                    graftText("init.confirm", "Create"),
                    onClick = { onEvent(InitEvent.Submit) },
                    enabled = state.canCreate,
                    loading = state.running,
                )
            }
        },
        dismiss = {
            CamoButton(
                graftText("init.cancel", "Cancel"),
                onClick = onClose,
                appearance = Appearance.Ghost,
                enabled = !state.running,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CamoTextField(
                    state.folder,
                    { onEvent(InitEvent.FolderChanged(it)) },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    label = graftText("init.folder", "Folder"),
                    error = state.issue?.takeIf {
                        it == InitIssue.FolderIsAFile || it == InitIssue.AlreadyRepository ||
                            it == InitIssue.FileExists
                    }?.let { issueText(it, state) },
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
                                runCatching { Paths.get(it) }.getOrNull()
                            },
                        )?.let { onEvent(InitEvent.FolderPicked(it)) }
                    },
                    modifier = Modifier.padding(top = 20.dp).height(Graft.tokens.density.formControl),
                    appearance = Appearance.Outline,
                    enabled = !state.running,
                )
            }
            if (state.issue == InitIssue.AlreadyRepository) {
                CamoButton(
                    graftText("init.openExisting", "Open it"),
                    onClick = { onEvent(InitEvent.OpenExisting) },
                    modifier = Modifier.height(Graft.tokens.density.control),
                )
            }
            CamoTextField(
                state.branch,
                { onEvent(InitEvent.BranchChanged(it)) },
                modifier = Modifier.width(240.dp),
                label = graftText("init.branch", "Default branch"),
                error = state.issue?.takeIf { it == InitIssue.BadBranch }?.let { issueText(it, state) },
                enabled = !state.running,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                onKeyboardAction = submit,
            )
            Choice(
                graftText("init.gitignore", ".gitignore"),
                state.gitignore ?: graftText("init.none", "None"),
                listOf<Pair<String?, String>>(null to graftText("init.none", "None")) +
                    state.gitignores.map { it to it },
                state.gitignore,
                !state.running,
            ) { onEvent(InitEvent.GitignoreChosen(it)) }
            Choice(
                graftText("init.license", "License"),
                state.licenses.firstOrNull { it.id == state.license }?.name ?: graftText("init.none", "None"),
                listOf<Pair<String?, String>>(null to graftText("init.none", "None")) +
                    state.licenses.map { it.id to it.name },
                state.license,
                !state.running,
            ) { onEvent(InitEvent.LicenseChosen(it)) }
            // The holder is only asked for by the licenses whose text has a copyright line.
            if (state.licenseNeedsHolder) {
                CamoTextField(
                    state.holder,
                    { onEvent(InitEvent.HolderChanged(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = graftText("init.holder", "Copyright holder"),
                    error = state.issue?.takeIf { it == InitIssue.NoHolder }?.let { issueText(it, state) },
                    enabled = !state.running,
                )
            }
            CamoCheckbox(state.readme, {
                onEvent(InitEvent.ReadmeToggled)
            }, label = graftText("init.readme", "Add a README"), enabled = !state.running)
            val commitIssue = state.firstCommitIssue
            CamoCheckbox(
                state.firstCommit && commitIssue == null,
                { onEvent(InitEvent.FirstCommitToggled) },
                label = graftText("init.firstCommit", "Make the first commit"),
                description = commitIssue?.let { firstCommitText(it) },
                enabled = !state.running && commitIssue == null,
            )
            partial?.let { Partial(it) }
            state.error?.let { Failure(it, onEvent) }
        }
    }
}

@Composable
private fun Choice(
    label: String,
    current: String,
    options: List<Pair<String?, String>>,
    selected: String?,
    enabled: Boolean,
    onChoose: (String?) -> Unit,
) {
    val items =
        listOf(
            CamoMenuGroup(
                options.map { (id, name) ->
                    CamoMenuItem(
                        name,
                        onClick = { onChoose(id) },
                        checked =
                        id == selected,
                    )
                },
                exclusive = true,
            ),
        )
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CamoText(
            label,
            modifier = Modifier.width(110.dp),
            style = Graft.tokens.dense,
            color = Camouflage.colors.onSurfaceVariant,
        )
        CamoMenuAnchor(items = items) { open ->
            CamoButton(
                "$current  ▾",
                onClick = open,
                modifier = Modifier.height(Graft.tokens.density.formControl),
                appearance = Appearance.Outline,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun Partial(partial: InitOutcome.Partial) {
    val what =
        when (val step = partial.step) {
            is InitStep.Writing -> step.file.fileName
            InitStep.Committing -> graftText("init.theCommit", "the first commit")
        }
    val message = (partial.error as? SinewException)?.displayMessage(Graft.localizer) ?: partial.error.message.orEmpty()
    CamoText(
        graftText(
            "init.partial",
            "The repository was created, but {what} couldn't be written: {why}",
            "what" to what,
            "why" to message,
        ),
        style = Graft.tokens.dense,
        color = Camouflage.colors.error,
    )
}

@Composable
private fun Failure(error: Throwable, onEvent: (InitEvent) -> Unit) {
    val message = (error as? SinewException)?.displayMessage(Graft.localizer) ?: error.message.orEmpty()
    val code = (error as? SinewException)?.code
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CamoText(
            if (code != null &&
                code !in message
            ) {
                "$message · $code"
            } else {
                message
            },
            style = Graft.tokens.dense,
            color = Camouflage.colors.error,
        )
        if (error is CommandOutput) {
            CamoButton(
                graftText("graph.showOutput", "Show output"),
                onClick = { onEvent(InitEvent.OutputShown(true)) },
                modifier = Modifier.height(Graft.tokens.density.control),
                appearance = Appearance.Ghost,
            )
        }
    }
}

@Composable
private fun firstCommitText(issue: FirstCommitIssue): String = when (issue) {
    FirstCommitIssue.NothingToCommit ->
        graftText("init.commit.nothing", "Choose a .gitignore, a license or a README to commit.")

    FirstCommitIssue.NoIdentity -> graftText("init.commit.identity", "Set your name and email first.")
}

@Composable
private fun issueText(issue: InitIssue, state: InitState): String = when (issue) {
    InitIssue.NoFolder -> graftText("init.issue.noFolder", "Choose a folder.")

    InitIssue.FolderIsAFile -> graftText("clone.issue.file", "This is a file, not a folder.")

    InitIssue.AlreadyRepository -> graftText("init.issue.repository", "This folder is already a repository.")

    InitIssue.FileExists ->
        graftText(
            "init.issue.exists",
            "{files} already exists in this folder.",
            "files" to state.taken.joinToString(", ") { it.fileName },
        )

    InitIssue.BadBranch -> graftText(
        "init.issue.branch",
        "A branch name can't be empty, hold spaces or start with a dash.",
    )

    InitIssue.NoHolder -> graftText("init.issue.holder", "Enter who holds the copyright.")
}
