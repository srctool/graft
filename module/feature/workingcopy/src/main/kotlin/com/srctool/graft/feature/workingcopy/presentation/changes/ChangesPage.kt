// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.presentation.changes

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.button.CamoButton
import com.srctool.camouflage.core.checkbox.CamoCheckbox
import com.srctool.camouflage.core.dialog.CamoDialog
import com.srctool.camouflage.core.dialog.CamoDialogFrame
import com.srctool.camouflage.core.icon.CamoIcon
import com.srctool.camouflage.core.iconbutton.CamoIconButton
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.skeleton.CamoSkeleton
import com.srctool.camouflage.core.text.CamoText
import com.srctool.camouflage.core.theme.Appearance
import com.srctool.graft.core.exception.CommandOutput
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.core.ui.theme.GraftIcon
import com.srctool.graft.core.ui.theme.graftIcon
import com.srctool.graft.feature.workingcopy.domain.api.models.ChangeRow
import com.srctool.graft.feature.workingcopy.domain.api.models.RowStatus
import com.srctool.graft.feature.workingcopy.domain.api.models.SectionKind
import com.srctool.sinew.exception.displayMessage
import com.srctool.sinew.models.SinewException

@Composable
internal fun ChangesPage(
    viewModel: ChangesViewModel,
    onOpenSubmodule: (path: String) -> Unit = {},
    onCommitKey: (amend: Boolean) -> Unit = {},
    onShowHistory: (path: String?) -> Unit = {},
    onSnapshotNow: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) { viewModel.onEvent(ChangesEvent.Opened) }
    ChangesContent(state, viewModel::onEvent, onOpenSubmodule, onCommitKey, HistoryKeys(onShowHistory, onSnapshotNow))
}

/** The Changes panel without its view model, for screenshot tests. */
@Composable
internal fun ChangesContent(
    state: ChangesState,
    onEvent: (ChangesEvent) -> Unit,
    onOpenSubmodule: (path: String) -> Unit = {},
    onCommitKey: (amend: Boolean) -> Unit = {},
    history: HistoryKeys = HistoryKeys(),
) {
    val colors = Camouflage.colors
    Column(Modifier.fillMaxSize().background(colors.surface)) {
        Header(state, onEvent, history)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.phase == ChangesState.Phase.Loading -> Skeletons()

                state.phase == ChangesState.Phase.Failed && state.model == null ->
                    Message(failureText(state.error), state.error, onEvent)

                state.isEmpty -> Message(
                    graftText("changes.empty", "No changes. Edit a file in the working tree and it shows here."),
                    null,
                    onEvent,
                    retry = false,
                )

                else -> Lines(state, onEvent, onOpenSubmodule, onCommitKey, history)
            }
        }
        Footer(state, onEvent)
    }
    state.discard?.let { DiscardDialog(it, onEvent) }
    if (state.outputShown) OutputDialog(state.error, onEvent)
}

/** The ways into Local History from the panel: for a file or everything, and Snapshot now. */
internal class HistoryKeys(val show: (path: String?) -> Unit = {}, val snapshot: () -> Unit = {})

@Composable
private fun Header(state: ChangesState, onEvent: (ChangesEvent) -> Unit, history: HistoryKeys) {
    val colors = Camouflage.colors
    val density = Graft.tokens.density
    val counts = counts(state)
    Row(
        Modifier.fillMaxWidth().height(
            density.control + 8.dp,
        ).background(colors.surfaceContainerLow).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CamoText(
            if (counts.isEmpty()) state.branch else "${state.branch}  ·  $counts",
            style = Graft.tokens.denseStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        PanelButton(GraftIcon.RECENTLY_VIEWED, graftText("history.title", "Local History"), enabled = true) {
            history.show(null)
        }
        val tree = graftText("changes.tree", "Show as tree")
        val flat = graftText("changes.flat", "Show as list")
        PanelButton(
            if (state.tree) GraftIcon.LIST else GraftIcon.TREE_VIEW,
            if (state.tree) flat else tree,
            enabled = true,
        ) { onEvent(ChangesEvent.TreeToggled) }
        PanelButton(GraftIcon.RENEW, graftText("changes.refresh", "Refresh"), enabled = state.busy == null) {
            onEvent(ChangesEvent.Retry)
        }
    }
}

@Composable
private fun PanelButton(icon: GraftIcon, label: String, enabled: Boolean, onClick: () -> Unit) {
    CamoIconButton(
        icon = { CamoIcon(graftIcon(icon), contentDescription = null, modifier = Modifier.size(14.dp)) },
        contentDescription = label,
        onClick = { if (enabled) onClick() },
        modifier = Modifier.size(Graft.tokens.density.control),
        appearance = Appearance.Ghost,
        tooltip = label,
    )
}

private fun counts(state: ChangesState): String {
    val model = state.model ?: return ""
    val staged = model.sections.firstOrNull { it.kind == SectionKind.Staged }?.rows?.size ?: 0
    val unstaged = model.sections.firstOrNull { it.kind == SectionKind.Unstaged }?.rows?.size ?: 0
    if (staged + unstaged == 0) return ""
    return "$staged staged, $unstaged unstaged"
}

@Composable
private fun Skeletons() {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(8) { CamoSkeleton(10.dp, width = (120 + (it * 41) % 160).dp) }
    }
}

@Composable
private fun Lines(
    state: ChangesState,
    onEvent: (ChangesEvent) -> Unit,
    onOpenSubmodule: (String) -> Unit,
    onCommitKey: (Boolean) -> Unit,
    history: HistoryKeys,
) {
    val colors = Camouflage.colors
    val list = rememberLazyListState()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val label = graftText(
        "changes.label",
        "Changes, {count} files",
        "count" to state.lines.count { it is PanelLine.File },
    )
    LaunchedEffect(state.cursor) {
        val index = state.cursor
        if (index >= 0 && list.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            list.scrollToItem(maxOf(index - 2, 0))
        }
    }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .alpha(if (state.refreshing) DIMMED else 1f)
            .onPreviewKeyEvent { event -> onListKey(event, state, onEvent, onCommitKey, history) }
            .focusable(interactionSource = interaction)
            .semantics { contentDescription = label }
            .drawWithContent {
                drawContent()
                if (focused) {
                    val w = 2.dp.toPx()
                    drawRect(
                        colors.primary,
                        Offset(w / 2, w / 2),
                        Size(size.width - w, size.height - w),
                        style = Stroke(w),
                    )
                }
            },
        state = list,
    ) {
        itemsIndexed(state.lines, key = { _, line -> line.key }) { index, line ->
            if (line is PanelLine.File) {
                val label = graftText("history.title", "Local History")
                ContextMenuArea(items = { listOf(ContextMenuItem(label) { history.show(line.row.path) }) }) {
                    LineRow(line, index == state.cursor, state, index, onEvent, onOpenSubmodule)
                }
            } else {
                LineRow(line, index == state.cursor, state, index, onEvent, onOpenSubmodule)
            }
        }
    }
}

private fun onListKey(
    event: androidx.compose.ui.input.key.KeyEvent,
    state: ChangesState,
    onEvent: (ChangesEvent) -> Unit,
    onCommitKey: (Boolean) -> Unit,
    history: HistoryKeys,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val mod = event.isMetaPressed || event.isCtrlPressed
    // Mod+K focuses the commit box; Mod+Shift+Alt+K turns Amend on there (Keyboard Shortcuts, "Changes and diff").
    if (mod && event.key == Key.K) {
        onCommitKey(event.isShiftPressed && event.isAltPressed)
        return true
    }
    val index = state.cursor
    val line = state.lines.getOrNull(index)
    // Alt+Shift+H: the file's local history; Mod+Alt+Y: snapshot now (Keyboard Shortcuts).
    if (!mod && event.isAltPressed && event.isShiftPressed && event.key == Key.H) {
        history.show((line as? PanelLine.File)?.row?.path)
        return true
    }
    if (mod && event.isAltPressed && event.key == Key.Y) {
        history.snapshot()
        return true
    }
    val handled: ChangesEvent =
        when {
            mod && event.isAltPressed && event.key == Key.Z -> ChangesEvent.DiscardAsked(index)

            mod -> return false

            event.key == Key.DirectionDown -> ChangesEvent.Moved(1)

            event.key == Key.DirectionUp -> ChangesEvent.Moved(-1)

            event.key == Key.MoveHome -> ChangesEvent.Moved(Int.MIN_VALUE / 2)

            event.key == Key.MoveEnd -> ChangesEvent.Moved(Int.MAX_VALUE / 2)

            event.key == Key.Enter -> ChangesEvent.Activated(maxOf(index, 0))

            event.key == Key.Spacebar -> ChangesEvent.StageToggled(index)

            // A folder opens with → and folds with ←, as in the other trees.
            event.key == Key.DirectionRight && line is PanelLine.Folder && !line.open -> ChangesEvent.Activated(index)

            event.key == Key.DirectionLeft && line is PanelLine.Folder && line.open -> ChangesEvent.Activated(index)

            else -> return false
        }
    onEvent(handled)
    return true
}

@Composable
private fun LineRow(
    line: PanelLine,
    selected: Boolean,
    state: ChangesState,
    index: Int,
    onEvent: (ChangesEvent) -> Unit,
    onOpenSubmodule: (String) -> Unit,
) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val shownOpen =
        (line as? PanelLine.File)?.let {
            it.row.path == state.open?.row?.path && sideOf(it.section) == state.open.side
        } ==
            true
    val background =
        when {
            selected -> colors.surfaceContainerHighest
            shownOpen -> colors.surfaceContainerHigh
            else -> colors.surfaceContainerLowest
        }
    val busy = state.busy != null
    val tap = Modifier.pointerInput(index) {
        detectTapGestures(onTap = {
            onEvent(ChangesEvent.CursorSet(index))
            if (line !is PanelLine.Header) onEvent(ChangesEvent.Activated(index))
        })
    }
    when (line) {
        is PanelLine.Header -> {
            val title = sectionTitle(line.section)
            val action =
                when (line.section) {
                    SectionKind.Staged -> graftText("changes.unstageAll", "Unstage all")
                    SectionKind.Unstaged -> graftText("changes.stageAll", "Stage all")
                    else -> null
                }
            val sectionLabel =
                graftText("changes.section.label", "{title}, {count} files", "title" to title, "count" to line.count)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(tokens.density.row)
                    .background(colors.surfaceContainerLow)
                    .padding(horizontal = 8.dp)
                    .semantics { contentDescription = sectionLabel },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CamoText(
                    "$title  ${line.count}",
                    style = tokens.denseStrong,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (action != null) {
                    CamoButton(
                        action,
                        onClick = { if (!busy) onEvent(ChangesEvent.SectionToggled(index)) },
                        appearance = Appearance.Ghost,
                        modifier = Modifier.height(tokens.density.control),
                    )
                }
            }
        }

        is PanelLine.Folder -> {
            val folderLabel =
                graftText(
                    "changes.folder.label",
                    "{name}, folder, {count} files",
                    "name" to line.node.name,
                    "count" to line.node.fileCount,
                )
            val folderState =
                if (line.open) {
                    graftText(
                        "changes.expanded",
                        "expanded",
                    )
                } else {
                    graftText("changes.collapsed", "collapsed")
                }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(tokens.density.row)
                    .background(background)
                    .then(tap)
                    .padding(start = (8 + line.depth * INDENT).dp, end = 8.dp)
                    .clearAndSetSemantics {
                        contentDescription = folderLabel
                        stateDescription = folderState
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CamoIcon(
                    graftIcon(if (line.open) GraftIcon.CHEVRON_DOWN else GraftIcon.CHEVRON_RIGHT),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                )
                CamoIcon(
                    graftIcon(if (line.open) GraftIcon.FOLDER_OPEN else GraftIcon.FOLDER),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                CamoText(
                    line.node.name,
                    style = tokens.dense,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                CamoText("${line.node.fileCount}", style = tokens.dense, color = colors.onSurfaceVariant)
            }
        }

        is PanelLine.File -> FileLine(line, background, tap, busy, index, state.tree, onEvent, onOpenSubmodule)
    }
}

private fun sideOf(section: SectionKind) = if (section == SectionKind.Staged) {
    com.srctool.graft.feature.workingcopy.domain.api.models.DiffSide.STAGED
} else {
    com.srctool.graft.feature.workingcopy.domain.api.models.DiffSide.UNSTAGED
}

@Composable
private fun FileLine(
    line: PanelLine.File,
    background: androidx.compose.ui.graphics.Color,
    tap: Modifier,
    busy: Boolean,
    index: Int,
    tree: Boolean,
    onEvent: (ChangesEvent) -> Unit,
    onOpenSubmodule: (String) -> Unit,
) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val row = line.row
    val staged = line.section == SectionKind.Staged
    val conflict = line.section == SectionKind.Conflicts
    val name = if (row.originalPath != null) "${row.originalPath.substringAfterLast('/')} → ${row.name}" else row.name
    val folder = if (tree) null else row.path.substringBeforeLast('/', "").ifEmpty { null }
    val moved = row.submodule?.let { submoduleText(it.commitChanged, it.trackedModified, it.untracked) }
    val toggleLabel = if (staged) graftText("changes.unstage", "Unstage") else graftText("changes.stage", "Stage")
    val description =
        graftText(
            "changes.file.label",
            "{name}, {status}, {where}{state}",
            "name" to name,
            "status" to statusWord(row.status),
            "where" to (row.path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { "in $it, " } ?: ""),
            "state" to
                when {
                    conflict -> graftText("changes.state.conflict", "conflict")
                    staged -> graftText("changes.state.staged", "staged")
                    line.partly -> graftText("changes.state.partly", "unstaged, also partly staged")
                    else -> graftText("changes.state.unstaged", "unstaged")
                },
        )
    // A submodule row keeps its children in the tree, so its Open button can be reached; other rows read as one.
    val describe: androidx.compose.ui.semantics.SemanticsPropertyReceiver.() -> Unit = {
        contentDescription = description
        if (!conflict) {
            role = Role.Checkbox
            toggleableState = if (staged) ToggleableState.On else ToggleableState.Off
            onClick(toggleLabel) {
                if (!busy) onEvent(ChangesEvent.StageToggled(index))
                true
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            // A submodule row has a second line for what changed in it.
            .heightIn(min = tokens.density.row)
            .background(background)
            .then(tap)
            .padding(start = (8 + line.depth * INDENT).dp, end = 8.dp)
            .then(
                if (row.submodule !=
                    null
                ) {
                    Modifier.semantics(properties = describe)
                } else {
                    Modifier.clearAndSetSemantics(describe)
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusLetter(row)
        // One cell takes the room, so the checkbox and Open sit at the row's end whatever the name's length.
        Column(Modifier.weight(1f).padding(vertical = if (moved != null) 3.dp else 0.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CamoText(
                    name,
                    style = tokens.dense,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                folder?.let {
                    CamoText(
                        it,
                        style = tokens.dense,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (line.partly) CamoText("◐", style = tokens.dense, color = colors.onSurfaceVariant)
            }
            moved?.let {
                CamoText(
                    it,
                    style = tokens.dense,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (row.submodule != null) {
            CamoButton(
                graftText("changes.open", "Open"),
                onClick = { onOpenSubmodule(row.path) },
                appearance = Appearance.Ghost,
                modifier = Modifier.height(tokens.density.control),
            )
        }
        if (!conflict) {
            CamoCheckbox(
                checked = staged,
                onCheckedChange = { if (!busy) onEvent(ChangesEvent.StageToggled(index)) },
                modifier = Modifier.size(width = 24.dp, height = 20.dp).clearAndSetSemantics {},
            )
        }
    }
}

@Composable
private fun submoduleText(commitChanged: Boolean, modified: Boolean, untracked: Boolean): String = listOfNotNull(
    if (commitChanged) graftText("changes.submodule.moved", "moved") else null,
    if (modified) graftText("changes.submodule.modified", "modified content") else null,
    if (untracked) graftText("changes.submodule.untracked", "untracked content") else null,
).joinToString(" · ")

@Composable
private fun statusWord(status: RowStatus): String = when (status) {
    RowStatus.MODIFIED -> graftText("changes.status.modified", "modified")
    RowStatus.ADDED -> graftText("changes.status.added", "added")
    RowStatus.DELETED -> graftText("changes.status.deleted", "deleted")
    RowStatus.RENAMED -> graftText("changes.status.renamed", "renamed")
    RowStatus.COPIED -> graftText("changes.status.copied", "copied")
    RowStatus.TYPE_CHANGED -> graftText("changes.status.type", "type changed")
    RowStatus.UNTRACKED -> graftText("changes.status.untracked", "untracked")
    RowStatus.CONFLICT -> graftText("changes.status.conflict", "conflicted")
}

@Composable
private fun StatusLetter(row: ChangeRow) {
    val colors = Camouflage.colors
    val (letter, color) =
        when (row.status) {
            RowStatus.MODIFIED -> "M" to colors.primary
            RowStatus.ADDED -> "A" to colors.success
            RowStatus.DELETED -> "D" to colors.error
            RowStatus.RENAMED -> "R" to colors.secondary
            RowStatus.COPIED -> "C" to colors.tertiary
            RowStatus.TYPE_CHANGED -> "T" to colors.warning
            RowStatus.UNTRACKED -> "?" to colors.onSurfaceVariant
            RowStatus.CONFLICT -> "U" to colors.error
        }
    Box(Modifier.size(16.dp).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        BasicText(letter, style = Graft.tokens.code.copy(color = color, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun sectionTitle(kind: SectionKind): String = when (kind) {
    SectionKind.Conflicts -> graftText("changes.conflicts", "Conflicts")
    SectionKind.Staged -> graftText("changes.staged", "Staged")
    SectionKind.Unstaged -> graftText("changes.unstaged", "Unstaged")
    is SectionKind.Changelist -> kind.name
}

@Composable
private fun Footer(state: ChangesState, onEvent: (ChangesEvent) -> Unit) {
    val colors = Camouflage.colors
    val toast = state.toast
    val busy = state.busy
    val error = state.error
    if (busy == null && toast == null && !(error != null && state.model != null)) return
    Row(
        Modifier.fillMaxWidth().background(colors.surfaceContainerLow).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            busy != null -> CamoText(busy, style = Graft.tokens.dense, modifier = Modifier.weight(1f))

            error != null && state.model != null -> {
                CamoText(
                    failureText(error),
                    style = Graft.tokens.dense,
                    color = colors.error,
                    modifier = Modifier.weight(1f),
                )
                if (error is CommandOutput) {
                    CamoButton(graftText("graph.showOutput", "Show output"), onClick = {
                        onEvent(ChangesEvent.OutputShown(true))
                    }, appearance = Appearance.Ghost)
                }
            }

            toast != null -> {
                CamoText(toast.text, style = Graft.tokens.dense, modifier = Modifier.weight(1f))
                if (toast.undo != null) {
                    CamoButton(graftText("changes.undo", "Undo"), onClick = {
                        onEvent(ChangesEvent.UndoClicked)
                    }, appearance = Appearance.Ghost)
                }
                CamoIconButton(
                    icon = {
                        CamoIcon(graftIcon(GraftIcon.CLOSE), contentDescription = null, modifier = Modifier.size(12.dp))
                    },
                    contentDescription = graftText("changes.dismiss", "Dismiss"),
                    onClick = { onEvent(ChangesEvent.ToastDismissed) },
                    modifier = Modifier.size(Graft.tokens.density.control),
                    appearance = Appearance.Ghost,
                )
            }
        }
    }
}

@Composable
internal fun failureText(error: Throwable?): String {
    val message = (error as? SinewException)?.displayMessage(Graft.localizer) ?: error?.message.orEmpty()
    val code = (error as? SinewException)?.code
    return if (code != null && code !in message) "$message · $code" else message
}

@Composable
private fun Message(text: String, error: Throwable?, onEvent: (ChangesEvent) -> Unit, retry: Boolean = true) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CamoText(text, style = Graft.tokens.dense, color = Camouflage.colors.onSurfaceVariant)
            if (error != null && retry) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val height = Modifier.height(Graft.tokens.density.control)
                    CamoButton(graftText("changes.retry", "Retry"), onClick = {
                        onEvent(ChangesEvent.Retry)
                    }, modifier = height)
                    if (error is CommandOutput) {
                        CamoButton(
                            graftText("graph.showOutput", "Show output"),
                            onClick = { onEvent(ChangesEvent.OutputShown(true)) },
                            modifier = height,
                            appearance = Appearance.Ghost,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscardDialog(pending: PendingDiscard, onEvent: (ChangesEvent) -> Unit) {
    val single = pending.paths.singleOrNull()
    val name = single?.substringAfterLast('/')
    val title =
        when {
            pending.deletes && single != null -> graftText(
                "changes.discard.deleteTitle",
                "Delete {name}?",
                "name" to name.orEmpty(),
            )

            pending.lines > 0 -> graftText(
                "changes.discard.linesTitle",
                "Discard {count} lines in {name}?",
                "count" to pending.lines,
                "name" to name.orEmpty(),
            )

            single != null -> graftText("changes.discard.title", "Discard changes to {name}?", "name" to name.orEmpty())

            else -> graftText(
                "changes.discard.manyTitle",
                "Discard changes to {count} files?",
                "count" to pending.paths.size,
            )
        }
    val message =
        if (pending.deletes) {
            graftText(
                "changes.discard.deleteMessage",
                "Untracked files are deleted. A copy is kept in Local History (before discard).",
            )
        } else {
            graftText("changes.discard.message", "A copy is kept in Local History (before discard).")
        }
    val cancel = { onEvent(ChangesEvent.DiscardCancelled) }
    CamoDialogFrame(onDismissRequest = cancel) {
        CamoDialog(
            title = title,
            message = message,
            confirm = {
                CamoButton(
                    if (pending.deletes) {
                        graftText(
                            "changes.discard.delete",
                            "Delete",
                        )
                    } else {
                        graftText("changes.discard.confirm", "Discard changes")
                    },
                    onClick = { onEvent(ChangesEvent.DiscardConfirmed) },
                )
            },
            dismiss = {
                CamoButton(graftText("changes.cancel", "Cancel"), onClick = cancel, appearance = Appearance.Ghost)
            },
        )
    }
}

@Composable
private fun OutputDialog(error: Throwable?, onEvent: (ChangesEvent) -> Unit) {
    val output = error as? CommandOutput ?: return
    val close = { onEvent(ChangesEvent.OutputShown(false)) }
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

private const val INDENT = 14
private const val DIMMED = 0.5f
