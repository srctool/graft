// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.srctool.camouflage.core.icon.CamoIcon
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.text.CamoText
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.core.ui.theme.GraftIcon
import com.srctool.graft.core.ui.theme.graftIcon

/** A palette result: a branch, a commit, an action. [matches] are the ranges of [title] the query matched. */
@Immutable
public data class PaletteItem(
    val id: String,
    val title: String,
    val detail: String? = null,
    val icon: GraftIcon? = null,
    /** Shown on action rows, as Find Action shows a command's shortcut. */
    val shortcut: String? = null,
    val matches: List<IntRange>? = null,
)

/** The results of one source; [loading] while the source is still searching. */
@Immutable
public data class PaletteGroup(
    val id: String,
    val title: String,
    val items: List<PaletteItem>,
    val loading: Boolean = false,
)

/**
 * The popup Search Everywhere and Find Action use (camouflage#76): anchored to the top of the window, a field over
 * the grouped results. Up and Down move through the results, Tab and Shift+Tab jump between groups, Enter picks, Esc
 * closes. The sources and their matching are the caller's; this is the shell.
 */
@Composable
public fun CommandPalette(
    query: String,
    onQueryChange: (String) -> Unit,
    groups: List<PaletteGroup>,
    onSelect: (PaletteItem) -> Unit,
    onDismiss: () -> Unit,
    placeholder: String,
) {
    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, 48),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        CommandPalettePanel(query, onQueryChange, groups, onSelect, onDismiss, placeholder)
    }
}

/** The palette's panel without its popup, for embedding and for screenshot tests. */
@Composable
public fun CommandPalettePanel(
    query: String,
    onQueryChange: (String) -> Unit,
    groups: List<PaletteGroup>,
    onSelect: (PaletteItem) -> Unit,
    onDismiss: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val items = groups.flatMap { it.items }
    var selected by remember { mutableIntStateOf(0) }
    LaunchedEffect(groups) { selected = selected.coerceIn(0, maxOf(0, items.lastIndex)) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { field.requestFocus() }
    val list = rememberLazyListState()
    val groupStarts = groups.runningFold(0) { at, g -> at + g.items.size }.dropLast(1)
    val searching = graftText("ui.palette.searching", "Searching…")
    val empty = graftText("ui.palette.empty", "Nothing found")

    Column(
        modifier
            .width(560.dp)
            .background(colors.surfaceContainerHigh)
            .border(1.dp, colors.outlineVariant)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> if (items.isNotEmpty()) selected = (selected + 1) % items.size

                    Key.DirectionUp -> if (items.isNotEmpty()) selected = (selected - 1 + items.size) % items.size

                    Key.Tab -> {
                        val starts = groupStarts.filterIndexed { i, _ -> groups[i].items.isNotEmpty() }
                        if (starts.isNotEmpty()) {
                            val current = starts.lastOrNull { it <= selected } ?: starts.first()
                            val i = starts.indexOf(current)
                            selected =
                                if (event.isShiftPressed) {
                                    starts[(i - 1 + starts.size) % starts.size]
                                } else {
                                    starts[
                                        (i + 1) %
                                            starts.size,
                                    ]
                                }
                        }
                    }

                    Key.Enter, Key.NumPadEnter -> items.getOrNull(selected)?.let(onSelect)

                    Key.Escape -> onDismiss()

                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        Row(
            Modifier.fillMaxWidth().height(
                tokens.density.formControl,
            ).background(colors.surfaceContainerHighest).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CamoIcon(
                graftIcon(GraftIcon.SEARCH),
                contentDescription = null,
                modifier = Modifier.size(tokens.density.icon),
                tint = colors.onSurfaceVariant,
            )
            Box(Modifier.padding(start = 8.dp).weight(1f)) {
                if (query.isEmpty()) {
                    CamoText(
                        placeholder,
                        style = Camouflage.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = Camouflage.typography.bodyMedium.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth().focusRequester(field).semantics {
                        contentDescription =
                            placeholder
                    },
                )
            }
        }
        LaunchedEffect(selected) { if (items.isNotEmpty()) list.animateScrollToItem(rowIndex(groups, selected)) }
        LazyColumn(Modifier.heightIn(max = 400.dp), state = list) {
            var flat = 0
            groups.forEach { group ->
                item(key = "h:${group.id}") {
                    CamoText(
                        group.title.uppercase(),
                        modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp).semantics { heading() },
                        style = tokens.denseStrong,
                        color = colors.onSurfaceVariant,
                    )
                }
                group.items.forEach { item ->
                    val index = flat++
                    item(key = "i:${group.id}:${item.id}") {
                        PaletteRow(item, index == selected, onClick = { onSelect(item) })
                    }
                }
                if (group.loading) {
                    item(key = "l:${group.id}") {
                        CamoText(
                            searching,
                            modifier = Modifier.padding(start = 28.dp, bottom = 4.dp),
                            style = tokens.dense,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
            if (items.isEmpty() && groups.none { it.loading }) {
                item(key = "empty") {
                    CamoText(
                        empty,
                        modifier = Modifier.padding(8.dp),
                        style = tokens.dense,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The list index of the [flat]th result, counting each group's header (and loading row) before it. */
private fun rowIndex(groups: List<PaletteGroup>, flat: Int): Int {
    var rows = 0
    var seen = 0
    for (group in groups) {
        rows++
        if (flat < seen + group.items.size) return rows + (flat - seen)
        rows += group.items.size + if (group.loading) 1 else 0
        seen += group.items.size
    }
    return rows
}

@Composable
private fun PaletteRow(item: PaletteItem, selected: Boolean, onClick: () -> Unit) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .height(tokens.density.row)
            .background(if (selected) colors.primaryContainer else colors.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val content = if (selected) colors.onPrimaryContainer else colors.onSurface
        Box(Modifier.size(tokens.density.icon)) {
            item.icon?.let {
                CamoIcon(
                    graftIcon(it),
                    contentDescription = null,
                    modifier = Modifier.size(tokens.density.icon),
                    tint = content,
                )
            }
        }
        BasicText(
            highlightMatches(item.title, item.matches),
            modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
            style = tokens.dense.copy(color = content),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        item.detail?.let {
            CamoText(
                it,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
                style = tokens.dense,
                color = if (selected) content else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        item.shortcut?.let {
            CamoText(
                it,
                modifier = Modifier.padding(start = 8.dp),
                style = tokens.dense,
                color = colors.onSurfaceVariant,
            )
        }
    }
}
