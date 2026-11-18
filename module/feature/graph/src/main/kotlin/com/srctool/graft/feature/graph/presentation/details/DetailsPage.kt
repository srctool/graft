// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.details

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.srctool.camouflage.core.button.CamoButton
import com.srctool.camouflage.core.dialog.CamoDialog
import com.srctool.camouflage.core.dialog.CamoDialogFrame
import com.srctool.camouflage.core.icon.CamoIcon
import com.srctool.camouflage.core.iconbutton.CamoIconButton
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.skeleton.CamoSkeleton
import com.srctool.camouflage.core.text.CamoText
import com.srctool.camouflage.core.theme.Appearance
import com.srctool.camouflage.core.tooltip.CamoTooltip
import com.srctool.graft.core.exception.CommandOutput
import com.srctool.graft.core.ui.components.SpeedSearchMode
import com.srctool.graft.core.ui.components.SpeedSearchState
import com.srctool.graft.core.ui.components.speedSearch
import com.srctool.graft.core.ui.git.AuthorInitials
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.core.ui.theme.GraftIcon
import com.srctool.graft.core.ui.theme.graftIcon
import com.srctool.graft.data.git.api.models.ChangedFile
import com.srctool.graft.data.git.api.models.CommitDetails
import com.srctool.graft.data.git.api.models.FileStatus
import com.srctool.graft.data.git.api.models.Signature
import com.srctool.graft.data.git.api.models.SignatureStatus
import com.srctool.graft.feature.graph.presentation.graph.Age
import com.srctool.graft.feature.graph.presentation.graph.age
import com.srctool.graft.feature.graph.presentation.graph.exactTime
import com.srctool.sinew.exception.displayMessage
import com.srctool.sinew.models.SinewException
import com.srctool.sinew.viewmodel.ObserveEffects

/** The details page: [viewModel]'s state and its clipboard effect. */
@Composable
internal fun DetailsPage(viewModel: DetailsViewModel) {
    val state by viewModel.state.collectAsState()

    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(viewModel) { viewModel.onEvent(DetailsEvent.Opened) }
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is DetailsEffect.CopyToClipboard -> clipboard.setText(AnnotatedString(effect.text))
        }
    }
    DetailsContent(state, viewModel::onEvent)
}

/** The page without its view model, for screenshot tests. */
@Composable
internal fun DetailsContent(state: DetailsState, onEvent: (DetailsEvent) -> Unit) {
    val colors = Camouflage.colors
    Box(Modifier.fillMaxSize().background(colors.surfaceContainerLowest)) {
        when (state.phase) {
            DetailsState.Phase.Nothing -> Note(graftText("details.nothing", "Select a commit to see its details."))

            DetailsState.Phase.Wip ->
                Note(
                    graftText(
                        "details.wip",
                        "Uncommitted changes, {count} files. The changes panel comes in a later version.",
                        "count" to state.wipFiles,
                    ),
                )

            DetailsState.Phase.Loading ->
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CamoSkeleton(14.dp, width = 220.dp)
                    CamoSkeleton(10.dp, width = 140.dp)
                    CamoSkeleton(10.dp, width = 180.dp)
                    repeat(4) { CamoSkeleton(10.dp, width = (160 + it * 23).dp) }
                }

            DetailsState.Phase.Failed -> Failed(state, onEvent)

            DetailsState.Phase.Loaded -> Loaded(state, state.details ?: return@Box, onEvent)
        }
    }
    if (state.outputShown) OutputDialog(state.error, onEvent)
}

@Composable
private fun Note(text: String) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        CamoText(text, style = Graft.tokens.dense, color = Camouflage.colors.onSurfaceVariant)
    }
}

@Composable
private fun Failed(state: DetailsState, onEvent: (DetailsEvent) -> Unit) {
    val error = state.error ?: return
    val message = (error as? SinewException)?.displayMessage(Graft.localizer) ?: error.message.orEmpty()
    val code = (error as? SinewException)?.code
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val height = Modifier.height(Graft.tokens.density.control)
            CamoButton(graftText("details.retry", "Retry"), onClick = {
                onEvent(DetailsEvent.Retry)
            }, modifier = height)
            if (error is CommandOutput) {
                CamoButton(
                    graftText("graph.showOutput", "Show output"),
                    onClick = { onEvent(DetailsEvent.OutputShown(true)) },
                    modifier = height,
                    appearance = Appearance.Ghost,
                )
            }
        }
    }
}

@Composable
private fun OutputDialog(error: Throwable?, onEvent: (DetailsEvent) -> Unit) {
    val output = error as? CommandOutput ?: return
    val close = { onEvent(DetailsEvent.OutputShown(false)) }
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

@Composable
private fun Loaded(state: DetailsState, details: CommitDetails, onEvent: (DetailsEvent) -> Unit) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val list = rememberLazyListState()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val filesLabel = graftText("details.files.label", "Changed files")
    // Typing in the file list jumps to the next file whose name matches (speed search, JUMP mode).
    val speed = remember { SpeedSearchState(SpeedSearchMode.JUMP) }
    LaunchedEffect(speed.query) {
        if (speed.query.isEmpty()) return@LaunchedEffect
        val from = maxOf(state.focusIndex, 0)
        val hit = (state.rows.drop(from) + state.rows.take(from)).firstOrNull { row ->
            row is FileRow.File && speed.matches(row.file.path) != null
        }
        if (hit != null) onEvent(DetailsEvent.Focused(hit.key))
    }
    LaunchedEffect(state.focus) {
        val index = state.focusIndex
        if (index < 0) return@LaunchedEffect
        // The header is item 0; files follow.
        if (list.layoutInfo.visibleItemsInfo.none { it.index == index + 1 }) list.scrollToItem(maxOf(index - 1, 0))
    }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .then(if (focused) Modifier.speedSearch(speed) else Modifier)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !focused) return@onPreviewKeyEvent false
                val handled: DetailsEvent =
                    when (event.key) {
                        Key.DirectionDown -> DetailsEvent.Moved(1)
                        Key.DirectionUp -> DetailsEvent.Moved(-1)
                        Key.DirectionRight -> DetailsEvent.Expand
                        Key.DirectionLeft -> DetailsEvent.Collapse
                        Key.Enter -> DetailsEvent.Activated(state.focus ?: return@onPreviewKeyEvent false)
                        else -> return@onPreviewKeyEvent false
                    }
                onEvent(handled)
                true
            },
        state = list,
    ) {
        item(key = "header") { Header(state, details, onEvent) }
        item(key = "files-header") { FilesHeader(state, details, onEvent) }
        if (details.files.isEmpty()) {
            item(key = "no-files") {
                CamoText(
                    graftText("details.files.none", "No file changes against the first parent."),
                    modifier = Modifier.padding(12.dp),
                    style = tokens.dense,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        item(key = "files-focus") {
            // The file list's focus target: arrows and Enter act on its rows while it has focus.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .focusable(interactionSource = interaction)
                    .semantics { contentDescription = filesLabel },
            )
        }
        items(state.rows, key = { it.key }) { row ->
            FileRowView(row, row.key == state.focus && focused, onEvent)
        }
    }
}

@Composable
private fun Header(state: DetailsState, details: CommitDetails, onEvent: (DetailsEvent) -> Unit) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val density = tokens.density
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CamoText(
            details.subject,
            modifier = Modifier.semantics { heading() },
            style = tokens.denseStrong.copy(fontSize = 16.sp, lineHeight = 22.sp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CamoText(
                details.id.short(8),
                style = tokens.code.copy(fontSize = tokens.dense.fontSize),
                color = colors.onSurfaceVariant,
            )
            val copy = graftText("details.copyHash", "Copy hash")
            CamoIconButton(
                icon = {
                    CamoIcon(graftIcon(GraftIcon.COPY), contentDescription = null, modifier = Modifier.size(14.dp))
                },
                contentDescription = copy,
                onClick = { onEvent(DetailsEvent.CopyHash) },
                modifier = Modifier.size(density.control),
                appearance = Appearance.Ghost,
                tooltip = copy,
            )
            if (details.parents.isNotEmpty()) {
                CamoText(
                    graftText("details.parents", "Parents:"),
                    style = tokens.dense,
                    color = colors.onSurfaceVariant,
                )
                details.parents.forEachIndexed { i, parent ->
                    CamoButton(
                        parent.short(),
                        onClick = { onEvent(DetailsEvent.ParentChosen(i)) },
                        modifier = Modifier.height(density.control),
                        appearance = Appearance.Ghost,
                    )
                }
            }
        }
        Person(details.author, state.now, committed = false)
        if (details.committer.email != details.author.email || details.committer.time != details.author.time) {
            Person(details.committer, state.now, committed = true)
        }
        SignatureLine(details.signature)
        val body = body(details)
        if (body.isNotEmpty()) {
            SelectionContainer {
                BasicText(
                    body,
                    modifier = Modifier.padding(top = 4.dp),
                    style = tokens.dense.copy(color = colors.onSurface),
                )
            }
        }
        if (details.trailers.isNotEmpty()) {
            Column(Modifier.padding(top = 2.dp)) {
                details.trailers.forEach { t ->
                    Row {
                        CamoText("${t.key}: ", style = tokens.denseStrong, color = colors.onSurfaceVariant)
                        CamoText(t.value, style = tokens.dense, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** The message after its subject, without the trailers shown below it. */
internal fun body(details: CommitDetails): String {
    val lines = details.message.substringAfter('\n', "").trim('\n').lines().toMutableList()
    val trailers = details.trailers.map { "${it.key}: ${it.value}" }.toSet()
    while (lines.isNotEmpty() && (lines.last().isBlank() || lines.last() in trailers)) lines.removeAt(lines.lastIndex)
    return lines.joinToString("\n").trim()
}

@Composable
private fun Person(who: Signature, now: Long, committed: Boolean) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val relative = ageText(age(who.time, now.takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000)))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (committed) {
            Spacer(Modifier.width(18.dp))
            CamoText(
                graftText("details.committedBy", "committed by"),
                style = tokens.dense,
                color = colors.onSurfaceVariant,
            )
        } else {
            AuthorInitials(who.name, who.email)
        }
        CamoText(who.name, style = tokens.denseStrong, maxLines = 1)
        CamoText(
            "<${who.email}>",
            style = tokens.dense,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        CamoTooltip(exactTime(who.time)) { CamoText(relative, style = tokens.dense, color = colors.onSurfaceVariant) }
    }
}

@Composable
private fun ageText(age: Age): String = when (age) {
    Age.JustNow -> graftText("graph.age.now", "just now")
    is Age.Minutes -> graftText("graph.age.minutes", "{count} min ago", "count" to age.count)
    is Age.Hours -> graftText("graph.age.hours", "{count} h ago", "count" to age.count)
    is Age.Days -> graftText("graph.age.days", "{count} d ago", "count" to age.count)
    is Age.Date -> age.text
}

@Composable
private fun SignatureLine(status: SignatureStatus) {
    if (status == SignatureStatus.NONE) return
    val colors = Camouflage.colors
    val (icon, color, text) =
        when (status) {
            SignatureStatus.GOOD ->
                Triple(GraftIcon.CHECKMARK, colors.success, graftText("details.sig.good", "Signed, good signature"))

            SignatureStatus.GOOD_UNKNOWN_VALIDITY ->
                Triple(
                    GraftIcon.CHECKMARK,
                    colors.warning,
                    graftText("details.sig.unknown", "Signed, good signature from a key of unknown validity"),
                )

            SignatureStatus.EXPIRED_SIGNATURE ->
                Triple(
                    GraftIcon.WARNING,
                    colors.warning,
                    graftText("details.sig.expiredSignature", "Signed, the signature has expired"),
                )

            SignatureStatus.EXPIRED_KEY ->
                Triple(
                    GraftIcon.WARNING,
                    colors.warning,
                    graftText("details.sig.expiredKey", "Signed with a key that has expired"),
                )

            SignatureStatus.REVOKED_KEY ->
                Triple(GraftIcon.ERROR, colors.error, graftText("details.sig.revoked", "Signed with a revoked key"))

            SignatureStatus.BAD -> Triple(GraftIcon.ERROR, colors.error, graftText("details.sig.bad", "Bad signature"))

            SignatureStatus.CANNOT_CHECK ->
                Triple(
                    GraftIcon.INFORMATION,
                    colors.onSurfaceVariant,
                    graftText("details.sig.cannotCheck", "Signed, the signature couldn't be checked"),
                )

            SignatureStatus.NONE -> return
        }
    Row(
        Modifier.clearAndSetSemantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CamoIcon(graftIcon(icon), contentDescription = null, modifier = Modifier.size(14.dp), tint = color)
        CamoText(text, style = Graft.tokens.dense, color = color)
    }
}

@Composable
private fun FilesHeader(state: DetailsState, details: CommitDetails, onEvent: (DetailsEvent) -> Unit) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val added = details.files.sumOf { it.additions ?: 0 }
    val removed = details.files.sumOf { it.deletions ?: 0 }
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant))
        Row(
            Modifier.fillMaxWidth().height(tokens.density.control + 8.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CamoText(
                graftText("details.files.count", "{count} files", "count" to details.files.size),
                style = tokens.denseStrong,
            )
            CamoText("+$added", style = tokens.dense, color = colors.success)
            CamoText("−$removed", style = tokens.dense, color = colors.error)
            Spacer(Modifier.weight(1f))
            ViewButton(GraftIcon.LIST, graftText("details.files.list", "List"), !state.tree) {
                onEvent(DetailsEvent.TreeChosen(false))
            }
            ViewButton(GraftIcon.TREE_VIEW, graftText("details.files.tree", "Tree"), state.tree) {
                onEvent(DetailsEvent.TreeChosen(true))
            }
        }
    }
}

@Composable
private fun ViewButton(icon: GraftIcon, label: String, on: Boolean, onClick: () -> Unit) {
    CamoIconButton(
        icon = { CamoIcon(graftIcon(icon), contentDescription = null, modifier = Modifier.size(14.dp)) },
        contentDescription = label,
        onClick = onClick,
        modifier = Modifier.size(Graft.tokens.density.control),
        appearance = Appearance.Ghost,
        checked = on,
        tooltip = label,
    )
}

@Composable
private fun FileRowView(row: FileRow, focused: Boolean, onEvent: (DetailsEvent) -> Unit) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val description = fileDescription(row)
    Row(
        Modifier
            .fillMaxWidth()
            .height(tokens.density.row)
            .background(if (focused) colors.surfaceContainerHighest else colors.surfaceContainerLowest)
            .pointerInput(row.key) {
                detectTapGestures(
                    onTap = { onEvent(DetailsEvent.Focused(row.key)) },
                    onDoubleTap = { onEvent(DetailsEvent.Activated(row.key)) },
                )
            }.clearAndSetSemantics {
                contentDescription = description
                selected = focused
            }.padding(start = (12 + 14 * row.depth).dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (row) {
            is FileRow.Folder -> {
                CamoIcon(
                    graftIcon(if (row.expanded) GraftIcon.CHEVRON_DOWN else GraftIcon.CHEVRON_RIGHT),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = colors.onSurfaceVariant,
                )
                CamoIcon(
                    graftIcon(GraftIcon.FOLDER),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = colors.onSurfaceVariant,
                )
                CamoText(row.name, style = tokens.dense, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            is FileRow.File -> {
                StatusBadge(row.file.status)
                val label =
                    if (row.file.oldPath != null && row.file.status in setOf(FileStatus.RENAMED, FileStatus.COPIED)) {
                        val old = if (row.depth > 0 ||
                            row.label != row.file.path
                        ) {
                            row.file.oldPath!!.substringAfterLast('/')
                        } else {
                            row.file.oldPath!!
                        }
                        "$old → ${row.label}"
                    } else {
                        row.label
                    }
                CamoText(
                    label,
                    style = tokens.dense,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Counts(row.file)
            }
        }
    }
}

@Composable
private fun Counts(file: ChangedFile) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    if (file.isBinary) {
        CamoText(graftText("details.files.binary", "binary"), style = tokens.dense, color = colors.onSurfaceVariant)
        return
    }
    if ((file.additions ?: 0) > 0) CamoText("+${file.additions}", style = tokens.dense, color = colors.success)
    if ((file.deletions ?: 0) > 0) CamoText("−${file.deletions}", style = tokens.dense, color = colors.error)
}

/** A file's status as a letter in a colored box: the letter carries it without the color (Accessibility). */
@Composable
internal fun StatusBadge(status: FileStatus) {
    val colors = Camouflage.colors
    val (letter, color) =
        when (status) {
            FileStatus.ADDED -> "A" to colors.success
            FileStatus.MODIFIED -> "M" to colors.primary
            FileStatus.DELETED -> "D" to colors.error
            FileStatus.RENAMED -> "R" to colors.secondary
            FileStatus.COPIED -> "C" to colors.tertiary
            FileStatus.TYPE_CHANGED -> "T" to colors.warning
            FileStatus.UNMERGED -> "U" to colors.error
            FileStatus.UNKNOWN -> "?" to colors.onSurfaceVariant
        }
    Box(
        Modifier.size(16.dp).background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            letter,
            style = Graft.tokens.denseStrong.copy(fontSize = 11.sp, color = color, fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun fileDescription(row: FileRow): String = when (row) {
    is FileRow.Folder ->
        graftText("branches.folder", "Folder {name}", "name" to row.path) +
            if (row.expanded) "" else ", " + graftText("branches.collapsed", "collapsed")

    is FileRow.File -> fileLabel(row.file)
}

/** "Modified src/Login.kt, 12 lines added, 3 removed" (Task 14 wireframe). */
@Composable
internal fun fileLabel(file: ChangedFile): String {
    val path = file.path
    val what =
        when (file.status) {
            FileStatus.ADDED -> graftText("details.file.added", "Added {path}", "path" to path)

            FileStatus.DELETED -> graftText("details.file.deleted", "Deleted {path}", "path" to path)

            FileStatus.RENAMED -> graftText(
                "details.file.renamed",
                "Renamed {old} to {path}",
                "old" to (file.oldPath ?: ""),
                "path" to path,
            )

            FileStatus.COPIED -> graftText(
                "details.file.copied",
                "Copied {old} to {path}",
                "old" to (file.oldPath ?: ""),
                "path" to path,
            )

            FileStatus.TYPE_CHANGED -> graftText("details.file.type", "Type changed {path}", "path" to path)

            else -> graftText("details.file.modified", "Modified {path}", "path" to path)
        }
    if (file.isBinary) return what + ", " + graftText("details.file.binary", "binary")
    val parts = mutableListOf(what)
    if ((file.additions ?: 0) >
        0
    ) {
        parts += graftText("details.file.addedLines", "{count} lines added", "count" to file.additions!!)
    }
    if ((file.deletions ?: 0) >
        0
    ) {
        parts += graftText("details.file.removedLines", "{count} removed", "count" to file.deletions!!)
    }
    return parts.joinToString(", ")
}
