// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.operations

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.banner.CamoBanner
import com.srctool.camouflage.core.button.CamoButton
import com.srctool.camouflage.core.dialog.CamoDialog
import com.srctool.camouflage.core.dialog.CamoDialogFrame
import com.srctool.camouflage.core.icon.CamoIcon
import com.srctool.camouflage.core.menu.CamoMenuAnchor
import com.srctool.camouflage.core.menu.CamoMenuGroup
import com.srctool.camouflage.core.menu.CamoMenuItem
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.skeleton.CamoSkeleton
import com.srctool.camouflage.core.text.CamoText
import com.srctool.camouflage.core.theme.Appearance
import com.srctool.camouflage.core.theme.Tone
import com.srctool.camouflage.core.tooltip.CamoTooltip
import com.srctool.graft.core.exception.CommandOutput
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.core.ui.theme.GraftIcon
import com.srctool.graft.core.ui.theme.graftIcon
import com.srctool.graft.feature.graph.domain.api.models.TimelineEntry
import com.srctool.graft.feature.graph.presentation.graph.Age
import com.srctool.graft.feature.graph.presentation.graph.age
import com.srctool.graft.feature.graph.presentation.graph.exactTime
import com.srctool.sinew.exception.displayMessage
import com.srctool.sinew.models.SinewException
import com.srctool.sinew.viewmodel.ObserveEffects
import java.text.NumberFormat

@Composable
internal fun OperationsPage(viewModel: OperationsViewModel) {
    val state by viewModel.state.collectAsState()

    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(viewModel) { viewModel.onEvent(OperationsEvent.Opened) }
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is OperationsEffect.CopyToClipboard -> clipboard.setText(AnnotatedString(effect.text))
        }
    }
    OperationsContent(state, viewModel::onEvent)
}

/** The timeline without its view model, for screenshot tests. */
@Composable
internal fun OperationsContent(state: OperationsState, onEvent: (OperationsEvent) -> Unit) {
    val colors = Camouflage.colors
    Column(Modifier.fillMaxSize().background(colors.surfaceContainerLowest)) {
        FilterBar(state, onEvent)
        if (!state.enabled) {
            CamoBanner(
                graftText(
                    "operations.disabled",
                    "Git isn't keeping reflogs in this repository, so new moves aren't recorded.",
                ),
                modifier = Modifier.fillMaxWidth(),
                tone = Tone.Warning,
            )
        }
        ColumnHeader()
        when (state.phase) {
            OperationsState.Phase.Loading ->
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(5) { CamoSkeleton(10.dp, width = (260 + it * 47).dp) }
                }

            OperationsState.Phase.Failed -> Failed(state.error, onEvent)

            OperationsState.Phase.Ready ->
                if (state.entries.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CamoText(
                            graftText("operations.empty", "No ref moves recorded yet."),
                            style = Graft.tokens.dense,
                            color = colors.onSurfaceVariant,
                        )
                    }
                } else {
                    Timeline(state, onEvent)
                }
        }
    }
    if (state.outputShown) OutputDialog(state.error, onEvent)
}

@Composable
private fun FilterBar(state: OperationsState, onEvent: (OperationsEvent) -> Unit) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val all = graftText("operations.allRefs", "all")
    val current = state.filter?.let(::shortRef) ?: all
    val items =
        listOf(
            CamoMenuGroup(
                listOf(
                    CamoMenuItem(
                        all,
                        onClick = { onEvent(OperationsEvent.FilterChosen(null)) },
                        checked =
                        state.filter == null,
                    ),
                ) +
                    state.refs.map { ref ->
                        CamoMenuItem(
                            shortRef(ref),
                            onClick = { onEvent(OperationsEvent.FilterChosen(ref)) },
                            checked =
                            state.filter == ref,
                        )
                    },
                exclusive = true,
            ),
        )
    Row(
        Modifier.fillMaxWidth().height(tokens.density.control + 6.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.weight(1f))
        CamoMenuAnchor(items = items) { open ->
            CamoButton(
                graftText("operations.refs", "Refs: {name}", "name" to current),
                onClick = open,
                modifier = Modifier.height(tokens.density.control),
                appearance = Appearance.Ghost,
                suffixIcon = {
                    CamoIcon(
                        graftIcon(GraftIcon.CHEVRON_DOWN),
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun ColumnHeader() {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    Row(
        Modifier.fillMaxWidth().height(
            tokens.density.row,
        ).background(colors.surfaceContainerLow).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Header(graftText("operations.when", "When"), Modifier.width(WHEN))
        Header(graftText("operations.refsColumn", "Refs"), Modifier.width(REFS))
        Header(graftText("operations.move", "Move"), Modifier.width(MOVE))
        Header(graftText("operations.message", "Message"), Modifier.weight(1f))
    }
}

private val WHEN = 110.dp
private val REFS = 190.dp
private val MOVE = 180.dp

@Composable
private fun Header(text: String, modifier: Modifier) {
    CamoText(
        text,
        modifier = modifier,
        style = Graft.tokens.denseStrong,
        color = Camouflage.colors.onSurfaceVariant,
        maxLines = 1,
    )
}

@Composable
private fun Timeline(state: OperationsState, onEvent: (OperationsEvent) -> Unit) {
    val colors = Camouflage.colors
    val list = rememberLazyListState()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val count = NumberFormat.getIntegerInstance().format(state.entries.size)
    val label = graftText("operations.label", "Operations, {count} moves", "count" to count)
    val nearEnd by remember {
        derivedStateOf {
            (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >=
                list.layoutInfo.totalItemsCount - 20
        }
    }
    LaunchedEffect(nearEnd, state.entries.size) { if (nearEnd) onEvent(OperationsEvent.NearEnd) }
    LaunchedEffect(state.selected) {
        val index = state.selected
        if (index < 0) return@LaunchedEffect
        if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.scrollToItem(maxOf(index - 2, 0))
    }
    val menu =
        listOf(
            CamoMenuItem(graftText("operations.compare", "Compare before and after"), onClick = {
                onEvent(OperationsEvent.CompareMove)
                onEvent(OperationsEvent.MenuShown(false))
            }, enabled = state.selectedEntry?.oldId != null),
            CamoMenuItem(graftText("operations.copy", "Copy hash"), onClick = {
                onEvent(OperationsEvent.CopyHash)
                onEvent(OperationsEvent.MenuShown(false))
            }),
        )
    val controller = remember { com.srctool.camouflage.core.menu.CamoMenuController() }
    LaunchedEffect(state.menuOpen) { if (state.menuOpen) controller.open() else controller.close() }
    LaunchedEffect(controller.isOpen) {
        if (!controller.isOpen &&
            state.menuOpen
        ) {
            onEvent(OperationsEvent.MenuShown(false))
        }
    }
    CamoMenuAnchor(items = menu, controller = controller) { _ ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val mod = event.isMetaPressed || event.isCtrlPressed
                    val page = maxOf(list.layoutInfo.visibleItemsInfo.size - 1, 1)
                    val handled: OperationsEvent =
                        when {
                            mod && event.key == Key.D -> OperationsEvent.CompareMove
                            mod && event.key == Key.C -> OperationsEvent.CopyHash
                            menuKey(event.key, event.isShiftPressed) -> OperationsEvent.MenuShown(true)
                            mod -> return@onPreviewKeyEvent false
                            event.key == Key.DirectionDown -> OperationsEvent.Moved(1)
                            event.key == Key.DirectionUp -> OperationsEvent.Moved(-1)
                            event.key == Key.PageDown -> OperationsEvent.Moved(page)
                            event.key == Key.PageUp -> OperationsEvent.Moved(-page)
                            event.key == Key.MoveHome -> OperationsEvent.Moved(Int.MIN_VALUE / 2)
                            event.key == Key.MoveEnd -> OperationsEvent.Moved(Int.MAX_VALUE / 2)
                            event.key == Key.Enter -> OperationsEvent.Selected(maxOf(state.selected, 0))
                            else -> return@onPreviewKeyEvent false
                        }
                    onEvent(handled)
                    true
                }.focusable(interactionSource = interaction)
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
            itemsIndexed(state.entries, key = { i, e -> "${e.newId.hex}-${e.time}-$i" }) { index, entry ->
                EntryRow(entry, index == state.selected, state.now, onSelect = {
                    onEvent(OperationsEvent.Selected(index))
                })
            }
            item(key = "end") { EndLine(state) }
        }
    }
}

@Composable
private fun EntryRow(entry: TimelineEntry, selected: Boolean, now: Long, onSelect: () -> Unit) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val age = age(entry.time, now.takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000))
    val drops = mayDropCommits(entry.message)
    val refs = entry.refs.map(::shortRef)
    val old = entry.oldId?.short()
    val label = entryLabel(refs, old, entry.newId.short(), longAge(age), entry.message, drops)
    Row(
        Modifier
            .fillMaxWidth()
            .height(tokens.density.row)
            .background(if (selected) colors.surfaceContainerHighest else colors.surfaceContainerLowest)
            .pointerInput(onSelect) { detectTapGestures(onTap = { onSelect() }) }
            .clearAndSetSemantics {
                contentDescription = label
                this.selected = selected
            }.padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CamoTooltip(exactTime(entry.time)) {
            CamoText(
                shortAge(age),
                modifier = Modifier.width(WHEN),
                style = tokens.dense,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Row(Modifier.width(REFS), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            refs.take(3).forEach { name ->
                CamoText(
                    name,
                    modifier = Modifier.background(colors.surfaceContainerHigh).padding(horizontal = 5.dp),
                    style = tokens.dense,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(Modifier.width(MOVE), verticalAlignment = Alignment.CenterVertically) {
            val code = tokens.code.copy(fontSize = tokens.dense.fontSize)
            if (old != null) {
                CamoText(old, style = code, color = colors.onSurfaceVariant)
            } else {
                CamoText(
                    graftText("operations.created", "created"),
                    style = tokens.dense,
                    color = colors.onSurfaceVariant,
                )
            }
            CamoText(" → ", style = tokens.dense, color = if (drops) colors.warning else colors.onSurfaceVariant)
            CamoText(entry.newId.short(), style = code)
        }
        CamoTooltip(entry.subject) {
            CamoText(entry.message, style = tokens.dense, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EndLine(state: OperationsState) {
    val text =
        when {
            state.loadingMore -> graftText("operations.loadingMore", "Loading more…")
            state.exhausted -> graftText("operations.end", "That's everything git kept. Reflogs expire after 90 days.")
            else -> ""
        }
    BasicText(
        text,
        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        style = Graft.tokens.dense.copy(color = Camouflage.colors.onSurfaceVariant),
    )
}

/** "main and HEAD moved from a1b2c3d to 9f8e7d6, 10 minutes ago, commit: Fix login, may have dropped commits". */
@Composable
private fun entryLabel(
    refs: List<String>,
    old: String?,
    new: String,
    age: String,
    message: String,
    drops: Boolean,
): String {
    val who =
        if (refs.size == 1) {
            refs[0]
        } else {
            val first = refs.dropLast(1).joinToString(", ")
            graftText("graph.row.and", "{first} and {last}", "first" to first, "last" to refs.last())
        }
    val moved =
        if (old != null) {
            graftText("operations.moved", "{refs} moved from {old} to {new}", "refs" to who, "old" to old, "new" to new)
        } else {
            graftText("operations.createdAt", "{refs} created at {new}", "refs" to who, "new" to new)
        }
    val parts = mutableListOf(moved, age, message)
    if (drops) parts += graftText("operations.drops", "may have dropped commits")
    return parts.joinToString(", ")
}

/** Shift+F10 or the menu key opens the context menu. */
private fun menuKey(key: Key, shift: Boolean): Boolean = (key == Key.F10 && shift) || key == Key.Menu

/** `HEAD`, `main`, `origin/main`: a ref without its namespace. */
internal fun shortRef(ref: String): String =
    ref.removePrefix("refs/heads/").removePrefix("refs/remotes/").removePrefix("refs/tags/")

@Composable
private fun shortAge(age: Age): String = when (age) {
    Age.JustNow -> graftText("graph.age.now", "just now")
    is Age.Minutes -> graftText("graph.age.minutes", "{count} min ago", "count" to age.count)
    is Age.Hours -> graftText("graph.age.hours", "{count} h ago", "count" to age.count)
    is Age.Days -> graftText("graph.age.days", "{count} d ago", "count" to age.count)
    is Age.Date -> age.text
}

@Composable
private fun longAge(age: Age): String = when (age) {
    Age.JustNow -> graftText("graph.age.now", "just now")
    is Age.Minutes -> graftText("graph.ageLong.minutes", "{count} minutes ago", "count" to age.count)
    is Age.Hours -> graftText("graph.ageLong.hours", "{count} hours ago", "count" to age.count)
    is Age.Days -> graftText("graph.ageLong.days", "{count} days ago", "count" to age.count)
    is Age.Date -> age.text
}

@Composable
private fun Failed(error: Throwable?, onEvent: (OperationsEvent) -> Unit) {
    val message = (error as? SinewException)?.displayMessage(Graft.localizer) ?: error?.message.orEmpty()
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
            CamoButton(graftText("diff.retry", "Retry"), onClick = {
                onEvent(OperationsEvent.Retry)
            }, modifier = height)
            if (error is CommandOutput) {
                CamoButton(
                    graftText("graph.showOutput", "Show output"),
                    onClick = { onEvent(OperationsEvent.OutputShown(true)) },
                    modifier = height,
                    appearance = Appearance.Ghost,
                )
            }
        }
    }
}

@Composable
private fun OutputDialog(error: Throwable?, onEvent: (OperationsEvent) -> Unit) {
    val output = error as? CommandOutput ?: return
    val close = { onEvent(OperationsEvent.OutputShown(false)) }
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
