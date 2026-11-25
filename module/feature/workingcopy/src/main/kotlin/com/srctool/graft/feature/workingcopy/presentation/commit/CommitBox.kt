// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.presentation.commit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.button.CamoButton
import com.srctool.camouflage.core.checkbox.CamoCheckbox
import com.srctool.camouflage.core.dialog.CamoDialog
import com.srctool.camouflage.core.dialog.CamoDialogFrame
import com.srctool.camouflage.core.icon.CamoIcon
import com.srctool.camouflage.core.iconbutton.CamoIconButton
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.text.CamoText
import com.srctool.camouflage.core.textfield.CamoTextField
import com.srctool.camouflage.core.theme.Appearance
import com.srctool.graft.core.exception.CommandOutput
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.core.ui.theme.GraftIcon
import com.srctool.graft.core.ui.theme.graftIcon
import com.srctool.graft.data.git.api.models.CommitGuide
import com.srctool.graft.data.git.api.models.GuardFinding
import com.srctool.graft.data.git.api.models.GuardRule
import com.srctool.graft.data.git.api.models.GuardSeverity
import com.srctool.graft.feature.workingcopy.presentation.changes.failureText
import com.srctool.sinew.viewmodel.ObserveEffects

/** The commit box and its dialogs, wired to [viewModel]. */
@Composable
internal fun CommitBoxPage(viewModel: CommitViewModel, focus: FocusRequester = remember { FocusRequester() }) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) { viewModel.onEvent(CommitEvent.Opened) }
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            CommitEffect.FocusSummary -> runCatching { focus.requestFocus() }
        }
    }
    CommitBox(state, viewModel::onEvent, focus)
}

/** The box without its view model, for screenshot tests. */
@Composable
internal fun CommitBox(
    state: CommitState,
    onEvent: (CommitEvent) -> Unit,
    summaryFocus: FocusRequester = remember { FocusRequester() },
) {
    val colors = Camouflage.colors
    val density = Graft.tokens.density
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerLow)
            .padding(8.dp)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val mod = event.isMetaPressed || event.isCtrlPressed
                when {
                    mod && event.key == Key.Enter -> {
                        onEvent(CommitEvent.Submitted)
                        true
                    }

                    event.key == Key.Escape && state.optionsOpen -> {
                        onEvent(CommitEvent.OptionsShown(false))
                        true
                    }

                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val amendLabel =
                if (state.previous != null) {
                    graftText("commit.amend.label", "Amend previous commit {id}", "id" to state.previousId)
                } else {
                    graftText("commit.amend.none", "Amend previous commit")
                }
            Box(Modifier.weight(1f).semantics { contentDescription = amendLabel }) {
                CamoCheckbox(
                    checked = state.amend,
                    onCheckedChange = { onEvent(CommitEvent.AmendToggled(it)) },
                    enabled = state.canAmend && state.busy == null,
                    label = graftText("commit.amend", "Amend previous commit"),
                )
            }
            CamoButton(
                if (state.optionsOn) {
                    graftText(
                        "commit.options.on",
                        "Options ●",
                    )
                } else {
                    graftText("commit.options", "Options")
                },
                onClick = { onEvent(CommitEvent.OptionsShown(!state.optionsOpen)) },
                appearance = Appearance.Ghost,
                modifier = Modifier.height(density.control),
            )
        }
        if (state.optionsOpen) Options(state, onEvent)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            val count = state.guide.summaryLength
            val summaryLabel =
                graftText("commit.summary.label", "Commit summary, {count} of 50 characters", "count" to count)
            CamoTextField(
                state.summary,
                { onEvent(CommitEvent.SummaryChanged(it)) },
                modifier = Modifier.weight(1f).focusRequester(summaryFocus).semantics {
                    contentDescription =
                        summaryLabel
                },
                label = graftText("commit.summary", "Summary"),
                enabled = state.busy == null,
            )
            CamoText(
                "$count/${CommitGuide.SUMMARY_LIMIT}",
                style = Graft.tokens.dense,
                color = if (state.guide.summaryTooLong) colors.warning else colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            )
        }
        Description(state, onEvent)
        state.guide.longDescriptionLines.firstOrNull()?.let { line ->
            CamoText(
                graftText("commit.guide.long", "Line {line} is over 72 characters", "line" to line),
                style = Graft.tokens.dense,
                color = colors.warning,
            )
        }
        if (state.coAuthors.isNotEmpty() && !state.optionsOpen) {
            state.coAuthors.forEachIndexed { index, author -> AuthorChip(author.toString(), index, onEvent) }
        }
        state.error?.let { error ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CamoText(
                    failureText(error),
                    style = Graft.tokens.dense,
                    color = colors.error,
                    modifier = Modifier.weight(1f),
                )
                if (error is CommandOutput) {
                    CamoButton(
                        graftText("graph.showOutput", "Show output"),
                        onClick = { onEvent(CommitEvent.OutputShown(true)) },
                        appearance = Appearance.Ghost,
                    )
                }
                CamoButton(graftText("commit.dismiss", "Dismiss"), onClick = {
                    onEvent(CommitEvent.ErrorDismissed)
                }, appearance = Appearance.Ghost)
            }
        }
        val blocker = state.blocker
        val label = buttonLabel(state)
        val reason = blocker?.let { blockerText(it) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CamoButton(
                label,
                onClick = { if (blocker == null) onEvent(CommitEvent.Submitted) },
                enabled = blocker == null,
                modifier = Modifier.height(density.formControl).semantics {
                    contentDescription =
                        if (reason != null && blocker != Blocker.BUSY) "$label, unavailable: $reason" else label
                },
            )
            if (reason != null && blocker != Blocker.BUSY) {
                CamoText(reason, style = Graft.tokens.dense, color = colors.onSurfaceVariant)
            } else {
                CamoText("Mod+K", style = Graft.tokens.dense, color = colors.onSurfaceVariant)
            }
        }
        state.toast?.let { toast ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CamoText(toast.text, style = Graft.tokens.dense, modifier = Modifier.weight(1f))
                if (toast.undo) {
                    CamoButton(graftText("commit.undo", "Undo"), onClick = {
                        onEvent(CommitEvent.UndoClicked)
                    }, appearance = Appearance.Ghost)
                }
                CamoIconButton(
                    icon = {
                        CamoIcon(graftIcon(GraftIcon.CLOSE), contentDescription = null, modifier = Modifier.size(12.dp))
                    },
                    contentDescription = graftText("commit.dismiss", "Dismiss"),
                    onClick = { onEvent(CommitEvent.ToastDismissed) },
                    modifier = Modifier.size(density.control),
                    appearance = Appearance.Ghost,
                )
            }
        }
    }
    state.guard?.let { GuardDialogContent(it, state.busy != null, onEvent) }
    if (state.outputShown) OutputDialog(state.error, onEvent)
}

@Composable
private fun buttonLabel(state: CommitState): String = when {
    state.busy != null && state.amend -> graftText("commit.amending", "Amending…")
    state.busy != null -> graftText("commit.committing", "Committing…")
    state.amend -> graftText("commit.amendButton", "Amend commit")
    state.staged > 0 -> graftText("commit.button.count", "Commit {count} files", "count" to state.staged)
    else -> graftText("commit.button", "Commit")
}

@Composable
private fun blockerText(blocker: Blocker): String = when (blocker) {
    Blocker.LOADING -> graftText("commit.reason.loading", "Reading the changes…")
    Blocker.BLANK_SUMMARY -> graftText("commit.reason.summary", "Write a summary")
    Blocker.NOTHING_STAGED -> graftText("commit.reason.nothing", "Nothing is staged")
    Blocker.CONFLICTS -> graftText("commit.reason.conflicts", "Resolve the conflicts first")
    Blocker.BUSY -> ""
}

@Composable
private fun Description(state: CommitState, onEvent: (CommitEvent) -> Unit) {
    val colors = Camouflage.colors
    val style = Graft.tokens.code.copy(color = colors.onSurface)
    val measurer = rememberTextMeasurer()
    val column = remember(style) { measurer.measure("M".repeat(CommitGuide.LINE_LIMIT), style).size.width.toFloat() }
    val label = graftText("commit.description.label", "Commit description")
    val shape = Modifier.border(1.dp, colors.outline)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp, max = 140.dp)
            .background(colors.surface)
            .then(shape)
            .padding(8.dp)
            .drawBehind {
                // The 72-column guide: a hint where a line is usually wrapped, never a limit.
                drawLine(colors.outlineVariant, Offset(column, 0f), Offset(column, size.height), 1f)
            },
    ) {
        BasicTextField(
            state.description,
            { onEvent(CommitEvent.DescriptionChanged(it)) },
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = label
            }.verticalScroll(rememberScrollState()),
            textStyle = style,
            cursorBrush = SolidColor(colors.primary),
            enabled = state.busy == null,
            keyboardOptions = KeyboardOptions.Default,
            keyboardActions = KeyboardActions.Default,
            decorationBox = { inner ->
                if (state.description.isEmpty()) {
                    CamoText(
                        graftText("commit.description", "Description"),
                        style = Graft.tokens.dense,
                        color = colors.onSurfaceVariant,
                    )
                }
                inner()
            },
        )
    }
}

@Composable
private fun Options(state: CommitState, onEvent: (CommitEvent) -> Unit) {
    val colors = Camouflage.colors
    val density = Graft.tokens.density
    Column(
        Modifier.fillMaxWidth().background(colors.surface).border(1.dp, colors.outlineVariant).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CamoCheckbox(state.signOff, {
            onEvent(CommitEvent.SignOffToggled(it))
        }, label = graftText("commit.signoff", "Sign off"))
        Column {
            CamoText(graftText("commit.coauthors", "Co-authors"), style = Graft.tokens.denseStrong)
            state.coAuthors.forEachIndexed { index, author -> AuthorChip(author.toString(), index, onEvent) }
            CamoTextField(
                state.coAuthorInput,
                { onEvent(CommitEvent.CoAuthorTyped(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = graftText("commit.coauthor.input", "Name <email>"),
                error = if (state.coAuthorInvalid) {
                    graftText(
                        "commit.coauthor.invalid",
                        "Write it as Name <email>",
                    )
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions.Default,
                onKeyboardAction = androidx.compose.foundation.text.input.KeyboardActionHandler {
                    onEvent(CommitEvent.CoAuthorAdded())
                },
            )
            if (state.recent.isNotEmpty()) {
                CamoText(
                    graftText("commit.recent", "Recent"),
                    style = Graft.tokens.dense,
                    color = colors.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.recent.filter { it !in state.coAuthors }.take(RECENT).forEach { author ->
                        CamoButton(
                            author.name,
                            onClick = { onEvent(CommitEvent.CoAuthorAdded(author)) },
                            appearance = Appearance.Ghost,
                            modifier = Modifier.height(density.control),
                        )
                    }
                }
            }
        }
        CamoCheckbox(state.noVerify, {
            onEvent(CommitEvent.NoVerifyToggled(it))
        }, label = graftText("commit.noverify", "Skip hooks (--no-verify)"))
        if (state.noVerify) {
            CamoText(
                graftText("commit.noverify.note", "Hooks don't run. The guards still do."),
                style = Graft.tokens.dense,
                color = colors.warning,
            )
        }
        CamoCheckbox(state.allowEmpty, {
            onEvent(CommitEvent.AllowEmptyToggled(it))
        }, label = graftText("commit.allowempty", "Allow an empty commit"))
    }
}

@Composable
private fun AuthorChip(text: String, index: Int, onEvent: (CommitEvent) -> Unit) {
    val remove = graftText("commit.coauthor.remove", "Remove co-author {name}", "name" to text)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        CamoText(
            "Co-authored-by: $text",
            style = Graft.tokens.dense,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        CamoIconButton(
            icon = { CamoIcon(graftIcon(GraftIcon.CLOSE), contentDescription = null, modifier = Modifier.size(12.dp)) },
            contentDescription = remove,
            onClick = { onEvent(CommitEvent.CoAuthorRemoved(index)) },
            modifier = Modifier.size(Graft.tokens.density.control),
            appearance = Appearance.Ghost,
        )
    }
}

// ---- the guard dialog (S10) ----------------------------------------------------------------------------------------

@Composable
private fun GuardDialogContent(dialog: GuardDialog, busy: Boolean, onEvent: (CommitEvent) -> Unit) {
    val colors = Camouflage.colors
    val cancel = { onEvent(CommitEvent.GuardCancelled) }
    val problems = dialog.findings.size
    val title =
        if (dialog.amend) {
            graftText("guard.title.amend", "Amend stopped: {count} problems", "count" to problems)
        } else {
            graftText("guard.title", "Commit stopped: {count} problems", "count" to problems)
        }
    val first = remember { FocusRequester() }
    LaunchedEffect(dialog.findings.firstOrNull()?.fingerprint) { runCatching { first.requestFocus() } }
    CamoDialogFrame(onDismissRequest = cancel) {
        CamoDialog(
            title = title,
            message =
            if (dialog.amend) {
                graftText("guard.message.amend", "Nothing was changed.")
            } else {
                graftText("guard.message", "Nothing was committed.")
            },
            confirm = {
                CamoButton(
                    if (dialog.amend) {
                        graftText(
                            "guard.anyway.amend",
                            "Amend anyway",
                        )
                    } else {
                        graftText("guard.anyway", "Commit anyway")
                    },
                    onClick = { onEvent(CommitEvent.CommitAnyway) },
                    enabled = dialog.canProceed && !busy,
                )
            },
            dismiss = {
                CamoButton(graftText("guard.cancel", "Cancel"), onClick = cancel, appearance = Appearance.Ghost)
            },
        ) {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                dialog.findings.forEachIndexed { index, finding ->
                    FindingRow(
                        finding,
                        dialog,
                        if (index ==
                            0
                        ) {
                            first
                        } else {
                            null
                        },
                        onEvent,
                        Modifier.fillMaxWidth().background(colors.surfaceContainerLow).padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FindingRow(
    finding: GuardFinding,
    dialog: GuardDialog,
    focus: FocusRequester?,
    onEvent: (CommitEvent) -> Unit,
    modifier: Modifier,
) {
    val colors = Camouflage.colors
    val block = finding.severity == GuardSeverity.BLOCK
    val answered = finding.fingerprint in dialog.answered
    val severity = if (block) graftText("guard.block", "Block") else graftText("guard.warn", "Warning")
    val location = finding.line?.let { "${finding.path} : $it" } ?: finding.path
    val description =
        graftText(
            "guard.finding.label",
            "{severity}. {label} in {where}. {excerpt}",
            "severity" to severity,
            "label" to finding.label,
            "where" to (finding.line?.let { "${finding.path} line $it" } ?: finding.path),
            "excerpt" to finding.excerpt,
        )
    Column(modifier.semantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CamoIcon(
                graftIcon(if (block) GraftIcon.ERROR else GraftIcon.WARNING),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            CamoText(
                "${severity.uppercase()}  ${finding.label}",
                style = Graft.tokens.denseStrong,
                color = if (block) colors.error else colors.warning,
                modifier = Modifier.weight(1f),
            )
            CamoText(
                location,
                style = Graft.tokens.dense,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (finding.rule == GuardRule.LARGE_FILE) {
            CamoText(
                graftText("guard.lfs", "Git LFS suits files over 10 MB."),
                style = Graft.tokens.dense,
                color = colors.onSurfaceVariant,
            )
        }
        BasicText(finding.excerpt, style = Graft.tokens.code.copy(color = colors.onSurface))
        val height = Modifier.height(Graft.tokens.density.control)
        if (dialog.asking == finding.fingerprint) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CamoText(graftText("guard.always.ask", "Allow always, for"), style = Graft.tokens.dense)
                CamoButton(graftText("guard.always.me", "You only"), onClick = {
                    onEvent(CommitEvent.AllowAlways(finding.fingerprint, false))
                }, modifier = height)
                CamoButton(graftText("guard.always.team", "The team"), onClick = {
                    onEvent(CommitEvent.AllowAlways(finding.fingerprint, true))
                }, modifier = height)
                CamoButton(graftText("guard.cancel", "Cancel"), onClick = {
                    onEvent(CommitEvent.AllowAlwaysAsked(null))
                }, appearance = Appearance.Ghost, modifier = height)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val firstFocus = if (focus != null) Modifier.focusRequester(focus) else Modifier
                if (finding.line != null) {
                    CamoButton(
                        graftText("guard.open", "Open at line"),
                        onClick = { onEvent(CommitEvent.OpenAtLine(finding.path)) },
                        appearance = Appearance.Ghost,
                        modifier = height.then(firstFocus),
                    )
                }
                CamoButton(
                    graftText("guard.unstage", "Unstage file"),
                    onClick = { onEvent(CommitEvent.UnstageFile(finding.path)) },
                    appearance = Appearance.Ghost,
                    modifier = height.then(if (finding.line == null) firstFocus else Modifier),
                )
                if (block) {
                    CamoButton(
                        if (answered) {
                            graftText(
                                "guard.allowed",
                                "Allowed once",
                            )
                        } else {
                            graftText("guard.once", "Allow once")
                        },
                        onClick = { if (!answered) onEvent(CommitEvent.AllowOnce(finding.fingerprint)) },
                        appearance = Appearance.Ghost,
                        modifier = height,
                    )
                    CamoButton(
                        graftText("guard.always", "Allow always…"),
                        onClick = { onEvent(CommitEvent.AllowAlwaysAsked(finding.fingerprint)) },
                        appearance = Appearance.Ghost,
                        modifier = height,
                    )
                }
            }
        }
    }
}

@Composable
private fun OutputDialog(error: Throwable?, onEvent: (CommitEvent) -> Unit) {
    val output = error as? CommandOutput ?: return
    val close = { onEvent(CommitEvent.OutputShown(false)) }
    CamoDialogFrame(onDismissRequest = close) {
        CamoDialog(
            message = output.argv.joinToString(" "),
            title = graftText("graph.output.title", "Git output"),
            confirm = { CamoButton(graftText("graph.output.close", "Close"), onClick = close) },
        ) {
            BasicText(output.stderr, style = Graft.tokens.code.copy(color = Camouflage.colors.onSurface))
        }
    }
}

private const val RECENT = 4
