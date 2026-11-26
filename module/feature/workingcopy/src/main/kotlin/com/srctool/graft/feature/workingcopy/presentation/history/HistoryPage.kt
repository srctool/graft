// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.presentation.history

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.button.CamoButton
import com.srctool.camouflage.core.dialog.CamoDialog
import com.srctool.camouflage.core.dialog.CamoDialogFrame
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.skeleton.CamoSkeleton
import com.srctool.camouflage.core.text.CamoText
import com.srctool.camouflage.core.textfield.CamoTextField
import com.srctool.camouflage.core.theme.Appearance
import com.srctool.graft.core.exception.CommandOutput
import com.srctool.graft.core.ui.git.DiffLineRef
import com.srctool.graft.core.ui.git.DiffMode
import com.srctool.graft.core.ui.git.DiffSelection
import com.srctool.graft.core.ui.git.DiffViewer
import com.srctool.graft.core.ui.git.HunkAction
import com.srctool.graft.core.ui.git.allChangedLines
import com.srctool.graft.core.ui.git.rememberDiffViewerState
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.data.git.api.models.FileStatus
import com.srctool.graft.feature.workingcopy.domain.api.models.HistoryDiff
import com.srctool.graft.feature.workingcopy.domain.api.models.HistorySearch
import com.srctool.graft.feature.workingcopy.presentation.changes.failureText
import com.srctool.graft.feature.workingcopy.presentation.changes.toDocument
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Local History in the center (S11, Task 14 wireframes), for [path] or the whole working copy. */
@Composable
internal fun HistoryPage(viewModel: HistoryViewModel, path: String?) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel, path) { viewModel.onEvent(HistoryEvent.Shown(path)) }
    HistoryContent(state, viewModel::onEvent)
}

/** The screen without its view model; [zone] and [today] are fixed by screenshot tests. */
@Composable
internal fun HistoryContent(
    state: HistoryState,
    onEvent: (HistoryEvent) -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
) {
    val colors = Camouflage.colors
    val clock = remember(zone, today) { Clock(zone, today) }
    Column(
        Modifier.fillMaxSize().background(colors.surface).onPreviewKeyEvent { event ->
            onScreenKey(event, state, onEvent)
        },
    ) {
        Header(state, onEvent)
        if (state.view == HistoryView.DELETED) FilterBar(state, onEvent) else SearchBar(state, onEvent)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.phase == HistoryState.Phase.Loading -> Skeletons()

                state.phase == HistoryState.Phase.Failed -> Info(failureText(state.error)) {
                    Button(graftText("changes.retry", "Retry")) { onEvent(HistoryEvent.Retry) }
                    if (state.error is CommandOutput) {
                        Button(graftText("graph.showOutput", "Show output"), Appearance.Ghost) {
                            onEvent(HistoryEvent.OutputShown(true))
                        }
                    }
                }

                state.view == HistoryView.DELETED -> DeletedBody(state, onEvent, clock)

                state.hits != null && state.entries.isEmpty() -> Info(
                    graftText("history.noMatches", "Nothing matches “{query}”.", "query" to state.query),
                ) {
                    Button(graftText("history.clearSearch", "Clear search")) { onEvent(HistoryEvent.SearchCleared) }
                }

                state.entries.isEmpty() && state.path != null ->
                    Info(graftText("history.emptyFile", "No snapshot has this file."))

                state.entries.isEmpty() -> Info(
                    graftText("history.empty", "No snapshots yet. Graft takes one shortly after files change."),
                ) {
                    Button(graftText("history.snapshotNow", "Snapshot now…")) { onEvent(HistoryEvent.SnapshotAsked) }
                }

                else -> TimelineBody(state, onEvent, clock)
            }
        }
        Footer(state, onEvent)
    }
    when (val dialog = state.dialog) {
        HistoryDialog.SnapshotNow -> SnapshotDialog(onEvent)
        is HistoryDialog.Purge -> PurgeDialog(dialog.path, onEvent)
        null -> Unit
    }
    if (state.outputShown) OutputDialog(state.error, onEvent)
}

private fun onScreenKey(event: KeyEvent, state: HistoryState, onEvent: (HistoryEvent) -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val mod = event.isMetaPressed || event.isCtrlPressed
    val handled: HistoryEvent =
        when {
            // Esc clears a search first, then goes back.
            event.key == Key.Escape ->
                if (state.hits != null || state.query.isNotEmpty()) HistoryEvent.SearchCleared else HistoryEvent.Closed

            event.isAltPressed && !mod && event.key == Key.DirectionLeft -> HistoryEvent.Closed

            mod && event.isAltPressed && event.key == Key.Y -> HistoryEvent.SnapshotAsked

            else -> return false
        }
    onEvent(handled)
    return true
}

/** How the screen writes a snapshot's time and day. */
private class Clock(private val zone: ZoneId, private val today: LocalDate) {
    private val texts = HistoryTexts.English(zone)

    fun time(seconds: Long): String = texts.time(seconds)

    fun day(seconds: Long): LocalDate = Instant.ofEpochSecond(seconds).atZone(zone).toLocalDate()

    fun isToday(day: LocalDate) = day == today

    fun isYesterday(day: LocalDate) = day == today.minusDays(1)
}

@Composable
private fun dayName(clock: Clock, day: LocalDate): String = when {
    clock.isToday(day) -> graftText("history.today", "Today")
    clock.isYesterday(day) -> graftText("history.yesterday", "Yesterday")
    else -> day.toString()
}

@Composable
private fun Button(
    text: String,
    appearance: Appearance = Appearance.Solid,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    CamoButton(
        text,
        onClick = { if (enabled) onClick() },
        modifier = modifier.height(Graft.tokens.density.control),
        appearance = appearance,
        enabled = enabled,
    )
}

@Composable
private fun Header(state: HistoryState, onEvent: (HistoryEvent) -> Unit) {
    val colors = Camouflage.colors
    val density = Graft.tokens.density
    val back = graftText("diff.back", "Back to the graph")
    Row(
        Modifier.fillMaxWidth().height(density.control + 10.dp).background(colors.surfaceContainerLow)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Button(
            graftText("diff.graph", "Graph"),
            Appearance.Ghost,
            Modifier.semantics { contentDescription = back },
        ) { onEvent(HistoryEvent.Closed) }
        CamoText(
            "›  ${graftText("history.title", "Local History")}" + (state.path?.let { "  ›  $it" } ?: ""),
            style = Graft.tokens.denseStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (state.path != null) {
            Button(graftText("history.allFiles", "All files"), Appearance.Ghost) {
                onEvent(HistoryEvent.AllFilesAsked)
            }
        } else {
            Choice(graftText("history.timeline", "Timeline"), state.view == HistoryView.TIMELINE) {
                onEvent(HistoryEvent.ViewChosen(HistoryView.TIMELINE))
            }
            Choice(graftText("history.deleted", "Deleted files"), state.view == HistoryView.DELETED) {
                onEvent(HistoryEvent.ViewChosen(HistoryView.DELETED))
            }
        }
        Button(graftText("history.snapshotNow", "Snapshot now…"), enabled = state.busy == null) {
            onEvent(HistoryEvent.SnapshotAsked)
        }
    }
}

/** One of two choices; the chosen one is solid and reads as selected. */
@Composable
private fun Choice(text: String, chosen: Boolean, onClick: () -> Unit) {
    Button(
        text,
        if (chosen) Appearance.Outline else Appearance.Ghost,
        Modifier.semantics { selected = chosen },
        onClick = onClick,
    )
}

@Composable
private fun SearchBar(state: HistoryState, onEvent: (HistoryEvent) -> Unit) {
    if (state.path != null) return
    val label = graftText("history.search", "Search snapshots")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CamoTextField(
            state.query,
            { onEvent(HistoryEvent.QueryTyped(it)) },
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
            label = label,
            onKeyboardAction = KeyboardActionHandler { onEvent(HistoryEvent.SearchRun) },
        )
        Choice(graftText("history.paths", "Paths"), state.kind == HistorySearch.PATHS) {
            onEvent(HistoryEvent.KindChosen(HistorySearch.PATHS))
        }
        Choice(graftText("history.text", "Text"), state.kind == HistorySearch.TEXT) {
            onEvent(HistoryEvent.KindChosen(HistorySearch.TEXT))
        }
        Button(graftText("history.find", "Search"), enabled = !state.searching) { onEvent(HistoryEvent.SearchRun) }
    }
}

@Composable
private fun FilterBar(state: HistoryState, onEvent: (HistoryEvent) -> Unit) {
    val label = graftText("history.filter", "Filter deleted files")
    CamoTextField(
        state.deletedFilter,
        { onEvent(HistoryEvent.FilterTyped(it)) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = label },
        label = label,
    )
}

@Composable
private fun Skeletons() {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(8) { CamoSkeleton(10.dp, width = (160 + (it * 47) % 220).dp) }
    }
}

@Composable
private fun Info(text: String, actions: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CamoText(text, style = Graft.tokens.dense, color = Camouflage.colors.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}

/** A focusable list moved with Up and Down; [onMove] selects the neighbour. */
@Composable
private fun <T> KeyList(
    items: List<T>,
    selected: Int,
    label: String,
    onMove: (Int) -> Unit,
    modifier: Modifier = Modifier,
    takesFocus: Boolean = false,
    row: @Composable (index: Int, item: T) -> Unit,
) {
    val colors = Camouflage.colors
    val list = rememberLazyListState()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    val focus = remember { FocusRequester() }
    if (takesFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(selected) {
        if (selected >= 0 && list.layoutInfo.visibleItemsInfo.none { it.index == selected }) {
            list.scrollToItem(maxOf(selected - 2, 0))
        }
    }
    LazyColumn(
        modifier
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> onMove(1)
                    Key.DirectionUp -> onMove(-1)
                    Key.MoveHome -> onMove(-items.size)
                    Key.MoveEnd -> onMove(items.size)
                    Key.Enter -> focusManager.moveFocus(FocusDirection.Next)
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            .focusRequester(focus)
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
        itemsIndexed(items) { index, item -> row(index, item) }
    }
}

@Composable
private fun Modifier.listRow(chosen: Boolean, description: String, onSelect: () -> Unit): Modifier = this
    .fillMaxWidth()
    .heightIn(min = Graft.tokens.density.row)
    .background(
        if (chosen) Camouflage.colors.surfaceContainerHighest else Camouflage.colors.surfaceContainerLowest,
    )
    .pointerInput(description) { detectTapGestures(onTap = { onSelect() }) }
    .padding(horizontal = 8.dp)
    .clearAndSetSemantics {
        contentDescription = description
        selected = chosen
        onClick {
            onSelect()
            true
        }
    }

@Composable
private fun TimelineBody(state: HistoryState, onEvent: (HistoryEvent) -> Unit, clock: Clock) {
    val colors = Camouflage.colors
    val entries = state.entries
    val label = graftText("history.snapshots", "Snapshots, {count}", "count" to entries.size)
    val automatic = graftText("history.automatic", "Automatic")
    val deleted = graftText("history.row.deleted", "deleted")
    Row(Modifier.fillMaxSize()) {
        KeyList(
            entries,
            entries.indexOfFirst { it.snapshot.id == state.selected },
            label,
            { onEvent(HistoryEvent.SnapshotMoved(it)) },
            Modifier.width(LIST_WIDTH).fillMaxHeight().background(colors.surfaceContainerLowest),
            takesFocus = true,
        ) { index, entry ->
            val day = clock.day(entry.snapshot.time)
            val name = entry.snapshot.label ?: automatic
            val time = clock.time(entry.snapshot.time)
            val dayText = dayName(clock, day)
            Column {
                if (index == 0 || clock.day(entries[index - 1].snapshot.time) != day) {
                    CamoText(
                        dayText,
                        style = Graft.tokens.denseStrong,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                val description = "$time $dayText, $name" + if (entry.deleted) ", $deleted" else ""
                Column(
                    Modifier.listRow(entry.snapshot.id == state.selected, description) {
                        onEvent(HistoryEvent.SnapshotSelected(entry.snapshot.id))
                    },
                    verticalArrangement = Arrangement.Center,
                ) {
                    CamoText(
                        "$time  $name" + if (entry.deleted) "  ($deleted)" else "",
                        style = Graft.tokens.dense,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    entry.matches.take(MATCHES).forEach {
                        CamoText(
                            it,
                            style = Graft.tokens.dense,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(colors.outlineVariant))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (state.path == null) SnapshotFiles(state, onEvent, clock)
            DiffArea(state, onEvent, clock, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SnapshotFiles(state: HistoryState, onEvent: (HistoryEvent) -> Unit, clock: Clock) {
    val colors = Camouflage.colors
    val snapshot = state.selectedSnapshot ?: return
    val files = state.files
    Row(
        Modifier.fillMaxWidth().background(colors.surfaceContainerLow).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val name = snapshot.label ?: graftText("history.automatic", "Automatic")
        CamoText(
            "${clock.time(snapshot.time)} · $name" +
                (files?.let { "   " + graftText("history.fileCount", "{count} files", "count" to it.size) } ?: ""),
            style = Graft.tokens.denseStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Button(graftText("history.restoreAll", "Restore all"), enabled = state.busy == null) {
            onEvent(HistoryEvent.AllRestored)
        }
    }
    if (files == null) {
        Box(Modifier.fillMaxWidth().height(FILES_HEIGHT).padding(8.dp)) { CamoSkeleton(10.dp, width = 220.dp) }
    } else {
        val label = graftText("history.files", "Files in this snapshot, {count}", "count" to files.size)
        KeyList(
            files,
            files.indexOfFirst { it.path == state.file },
            label,
            { onEvent(HistoryEvent.FileMoved(it)) },
            Modifier.fillMaxWidth().height(FILES_HEIGHT),
        ) { _, file ->
            val letter = letterOf(file.status)
            val word = wordOf(file.status)
            Row(
                Modifier.listRow(file.path == state.file, "${file.path}, $word") {
                    onEvent(HistoryEvent.FileSelected(file.path))
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicText(letter, style = Graft.tokens.code.copy(color = colors.onSurfaceVariant))
                CamoText(file.path, style = Graft.tokens.dense, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant))
}

private fun letterOf(status: FileStatus) = when (status) {
    FileStatus.ADDED -> "A"
    FileStatus.DELETED -> "D"
    FileStatus.RENAMED -> "R"
    FileStatus.COPIED -> "C"
    FileStatus.TYPE_CHANGED -> "T"
    FileStatus.UNMERGED -> "U"
    FileStatus.MODIFIED, FileStatus.UNKNOWN -> "M"
}

@Composable
private fun wordOf(status: FileStatus): String = when (status) {
    FileStatus.ADDED -> graftText("changes.status.added", "added")
    FileStatus.DELETED -> graftText("changes.status.deleted", "deleted")
    FileStatus.RENAMED -> graftText("changes.status.renamed", "renamed")
    FileStatus.COPIED -> graftText("changes.status.copied", "copied")
    FileStatus.TYPE_CHANGED -> graftText("changes.status.type", "type changed")
    FileStatus.UNMERGED -> graftText("changes.status.conflict", "conflicted")
    FileStatus.MODIFIED, FileStatus.UNKNOWN -> graftText("changes.status.modified", "modified")
}

@Composable
private fun DeletedBody(state: HistoryState, onEvent: (HistoryEvent) -> Unit, clock: Clock) {
    val colors = Camouflage.colors
    val shown = state.shownDeleted
    if (shown.isEmpty()) {
        Info(
            if (state.deleted.isEmpty()) {
                graftText("history.noDeleted", "No deleted files in the history.")
            } else {
                graftText("history.noMatches", "Nothing matches “{query}”.", "query" to state.deletedFilter)
            },
        )
        return
    }
    val label = graftText("history.deletedList", "Deleted files, {count}", "count" to shown.size)
    Row(Modifier.fillMaxSize()) {
        KeyList(
            shown,
            shown.indexOfFirst { it.path == state.file },
            label,
            { onEvent(HistoryEvent.FileMoved(it)) },
            Modifier.width(LIST_WIDTH).fillMaxHeight().background(colors.surfaceContainerLowest),
            takesFocus = true,
        ) { _, file ->
            val day = dayName(clock, clock.day(file.lastSeen.time))
            val seen = graftText(
                "history.lastSeen",
                "last seen {day} {time}",
                "day" to day,
                "time" to clock.time(file.lastSeen.time),
            )
            Column(
                Modifier.listRow(file.path == state.file, "${file.path}, $seen") {
                    onEvent(HistoryEvent.FileSelected(file.path))
                },
                verticalArrangement = Arrangement.Center,
            ) {
                CamoText(file.path, style = Graft.tokens.dense, maxLines = 1, overflow = TextOverflow.Ellipsis)
                CamoText(seen, style = Graft.tokens.dense, color = colors.onSurfaceVariant, maxLines = 1)
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(colors.outlineVariant))
        Column(Modifier.weight(1f).fillMaxHeight()) { DiffArea(state, onEvent, clock, Modifier.weight(1f)) }
    }
}

/** The selected file compared with now, and what can be done with it. */
@Composable
private fun DiffArea(state: HistoryState, onEvent: (HistoryEvent) -> Unit, clock: Clock, modifier: Modifier) {
    val colors = Camouflage.colors
    val file = state.file
    val snapshot = state.selectedSnapshot
    var mode by remember { mutableStateOf(DiffMode.UNIFIED) }
    val viewer = rememberDiffViewerState()
    val body = state.body
    val text = (body as? HistoryBody.Ready)?.diff as? HistoryDiff.Text
    val idle = state.busy == null
    if (file == null || snapshot == null) {
        Box(modifier.fillMaxWidth()) { Info(graftText("history.pick", "Select a file to compare it with now.")) }
        return
    }
    val name = file.substringAfterLast('/')
    val time = clock.time(snapshot.time)
    val title = graftText("history.compare", "{name} · {time} → now", "name" to name, "time" to time)
    val spoken = graftText("history.compare.label", "{name}, {time} compared with now", "name" to name, "time" to time)
    Row(
        Modifier.fillMaxWidth().background(colors.surfaceContainerLow).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CamoText(
            title,
            style = Graft.tokens.denseStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { contentDescription = spoken },
        )
        if (text != null) {
            Button(
                if (mode == DiffMode.UNIFIED) {
                    graftText("diff.split", "Side by side")
                } else {
                    graftText("diff.unified", "Unified")
                },
                Appearance.Ghost,
            ) { mode = if (mode == DiffMode.UNIFIED) DiffMode.SIDE_BY_SIDE else DiffMode.UNIFIED }
        }
    }
    Box(modifier.fillMaxWidth()) {
        when (body) {
            null, HistoryBody.Loading -> Skeletons()

            is HistoryBody.Failed -> Info(failureText(body.error)) {
                Button(graftText("changes.retry", "Retry")) { onEvent(HistoryEvent.Retry) }
            }

            is HistoryBody.Ready -> when (val diff = body.diff) {
                is HistoryDiff.Text -> {
                    val document = remember(diff) { toDocument(diff.diff) }
                    // Hunks are what can be ticked, so a ticked hunk shows all of its lines as ticked.
                    val selected: Set<DiffLineRef> =
                        document.allChangedLines().filterTo(HashSet()) { it.hunk in state.ticks }
                    val restoreHunk = graftText("history.restoreHunk", "Restore hunk")
                    DiffViewer(
                        document,
                        Modifier.fillMaxSize(),
                        mode = mode,
                        state = viewer,
                        selection = DiffSelection(
                            selected = selected,
                            onToggleHunk = { onEvent(HistoryEvent.HunkTicked(it)) },
                            onToggleLine = {},
                            lines = false,
                            hunkAction = if (state.canRestoreFile) {
                                HunkAction(restoreHunk) { if (idle) onEvent(HistoryEvent.HunkRestored(it)) }
                            } else {
                                null
                            },
                        ),
                    )
                }

                is HistoryDiff.Binary -> Info(graftText("history.binary", "Binary file. It differs from now."))

                is HistoryDiff.TooLarge -> Info(
                    graftText("history.tooLarge", "This file is over the inline diff limit."),
                ) {
                    Button(graftText("changes.showAnyway", "Show diff anyway")) { onEvent(HistoryEvent.ShowAnyway) }
                }

                HistoryDiff.Same -> Info(
                    graftText("history.same", "{name} is the same now as at {time}.", "name" to name, "time" to time),
                )
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().background(colors.surfaceContainerLow).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(graftText("history.restoreFile", "Restore file"), enabled = idle && state.canRestoreFile) {
            onEvent(HistoryEvent.FileRestored)
        }
        if (state.path != null) {
            Button(graftText("history.purge", "Remove from Local History…"), Appearance.Ghost, enabled = idle) {
                onEvent(HistoryEvent.PurgeAsked)
            }
        }
        Box(Modifier.weight(1f))
        if (state.ticks.isNotEmpty()) {
            val count = state.ticks.size
            val one = count == 1
            CamoText(
                if (one) {
                    graftText("history.tickedOne", "1 hunk ticked")
                } else {
                    graftText("history.ticked", "{count} hunks ticked", "count" to count)
                },
                style = Graft.tokens.dense,
            )
            Button(
                if (one) {
                    graftText("history.restoreTickedOne", "Restore 1 hunk")
                } else {
                    graftText("history.restoreTicked", "Restore {count} hunks", "count" to count)
                },
                enabled = idle && state.canRestoreFile,
            ) { onEvent(HistoryEvent.TickedRestored) }
        }
    }
}

@Composable
private fun Footer(state: HistoryState, onEvent: (HistoryEvent) -> Unit) {
    val colors = Camouflage.colors
    val busy = state.busy
    val toast = state.toast
    val error = state.error.takeIf { state.phase == HistoryState.Phase.Ready }
    if (busy == null && toast == null && error == null) return
    Row(
        Modifier.fillMaxWidth().background(colors.surfaceContainerHigh).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            busy != null -> CamoText(busy, style = Graft.tokens.dense, modifier = Modifier.weight(1f))

            error != null -> {
                CamoText(
                    failureText(error),
                    style = Graft.tokens.dense,
                    color = colors.error,
                    modifier = Modifier.weight(1f),
                )
                if (error is CommandOutput) {
                    Button(graftText("graph.showOutput", "Show output"), Appearance.Ghost) {
                        onEvent(HistoryEvent.OutputShown(true))
                    }
                }
            }

            toast != null -> {
                CamoText(toast.text, style = Graft.tokens.dense, modifier = Modifier.weight(1f))
                if (toast.undo != null) {
                    Button(graftText("changes.undo", "Undo"), Appearance.Ghost) { onEvent(HistoryEvent.UndoClicked) }
                }
                Button(graftText("changes.dismiss", "Dismiss"), Appearance.Ghost) {
                    onEvent(HistoryEvent.ToastDismissed)
                }
            }
        }
    }
}

@Composable
private fun SnapshotDialog(onEvent: (HistoryEvent) -> Unit) {
    var label by remember { mutableStateOf("") }
    val cancel = { onEvent(HistoryEvent.DialogCancelled) }
    val take = { onEvent(HistoryEvent.SnapshotConfirmed(label)) }
    val field = graftText("history.label", "Label (optional)")
    CamoDialogFrame(onDismissRequest = cancel) {
        CamoDialog(
            title = graftText("history.snapshot.title", "Snapshot now"),
            message = graftText("history.snapshot.message", "A labelled snapshot is kept until you remove it."),
            confirm = { Button(graftText("history.snapshot.confirm", "Snapshot"), onClick = take) },
            dismiss = { Button(graftText("changes.cancel", "Cancel"), Appearance.Ghost, onClick = cancel) },
        ) {
            CamoTextField(
                label,
                { label = it },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = field },
                label = field,
                onKeyboardAction = KeyboardActionHandler { take() },
            )
        }
    }
}

@Composable
private fun PurgeDialog(path: String, onEvent: (HistoryEvent) -> Unit) {
    val cancel = { onEvent(HistoryEvent.DialogCancelled) }
    CamoDialogFrame(onDismissRequest = cancel) {
        CamoDialog(
            title = graftText(
                "history.purge.title",
                "Remove {name} from Local History?",
                "name" to path.substringAfterLast('/'),
            ),
            message = graftText(
                "history.purge.message",
                "Every snapshot forgets {path}, and this can't be undone. The file in your folder stays. Git deletes " +
                    "the old objects later, when it cleans up.",
                "path" to path,
            ),
            confirm = {
                Button(graftText("history.purge.confirm", "Remove from history")) {
                    onEvent(HistoryEvent.PurgeConfirmed)
                }
            },
            dismiss = { Button(graftText("changes.cancel", "Cancel"), Appearance.Ghost, onClick = cancel) },
        )
    }
}

@Composable
private fun OutputDialog(error: Throwable?, onEvent: (HistoryEvent) -> Unit) {
    val output = error as? CommandOutput ?: return
    val close = { onEvent(HistoryEvent.OutputShown(false)) }
    CamoDialogFrame(onDismissRequest = close) {
        CamoDialog(
            message = output.argv.joinToString(" "),
            title = graftText("graph.output.title", "Git output"),
            confirm = { Button(graftText("graph.output.close", "Close"), onClick = close) },
        ) {
            BasicText(output.stderr, style = Graft.tokens.code.copy(color = Camouflage.colors.onSurface))
        }
    }
}

private val LIST_WIDTH = 240.dp
private val FILES_HEIGHT = 96.dp
private const val MATCHES = 3
