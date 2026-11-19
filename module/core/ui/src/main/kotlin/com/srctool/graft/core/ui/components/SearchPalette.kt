// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.button.CamoButton
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.search.CamoSearchField
import com.srctool.camouflage.core.skeleton.CamoSkeleton
import com.srctool.camouflage.core.text.CamoText
import com.srctool.camouflage.core.theme.Appearance
import com.srctool.graft.core.ui.theme.Graft

/** One tab of the palette: its label with the count, and whether it's the chosen one. */
public data class SearchPaletteTab(val id: String, val label: String, val selected: Boolean, val spoken: String)

/** A result row. [marks] are the indexes of [title] the search matched; [spoken] is the whole row for a screen reader. */
public data class SearchPaletteRow(
    val key: String,
    val title: String,
    val marks: List<Int> = emptyList(),
    val detail: String? = null,
    val trailing: String? = null,
    val spoken: String = title,
)

/** What a section shows besides its rows. */
public sealed interface SearchPaletteStatus {
    public data object Ready : SearchPaletteStatus

    /** One skeleton row while the group still works. */
    public data object Loading : SearchPaletteStatus

    /** The group failed; [retry] asks again. */
    public data class Failed(val message: String, val retryLabel: String, val retry: () -> Unit) : SearchPaletteStatus
}

/** A titled group of rows, with an optional [note] under them. */
public data class SearchPaletteSection(
    val title: String,
    val rows: List<SearchPaletteRow>,
    val status: SearchPaletteStatus = SearchPaletteStatus.Ready,
    val note: String? = null,
)

/**
 * The results palette of Search Everywhere and Find Action (the Compare picker has its own, smaller panel): a search box, tabs, grouped results with the match
 * marked, and a footer. Every key is handled in the box, so typing and moving never leave it: ↑ ↓ Home End move across
 * the groups, Enter opens, Tab and Shift+Tab (or Alt+←/→) change tab, Mod+Backspace clears, Esc is [onEscape].
 * [selected] counts rows across sections.
 */
@Composable
public fun SearchPalette(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    boxLabel: String,
    tabs: List<SearchPaletteTab>,
    onTab: (String) -> Unit,
    onTabMove: (Int) -> Unit,
    sections: List<SearchPaletteSection>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onMove: (Int) -> Unit,
    onActivate: () -> Unit,
    onEscape: () -> Unit,
    onClearText: () -> Unit,
    footer: String,
    emptyText: String?,
    hint: String?,
    listLabel: String,
    modifier: Modifier = Modifier,
) {
    val colors = Camouflage.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val list = rememberLazyListState()
    val lines = remember(sections) { lines(sections) }
    val selectedLine = lines.indexOfFirst { it is Line.Result && it.index == selected }
    LaunchedEffect(selectedLine) {
        if (selectedLine >=
            0
        ) {
            list.animateScrollToItem((selectedLine - 2).coerceAtLeast(0))
        }
    }
    Column(
        modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val mod = event.isMetaPressed || event.isCtrlPressed
            when {
                event.key == Key.DirectionDown -> onMove(1)
                event.key == Key.DirectionUp -> onMove(-1)
                event.key == Key.MoveHome && !mod -> onMove(Int.MIN_VALUE / 2)
                event.key == Key.MoveEnd && !mod -> onMove(Int.MAX_VALUE / 2)
                event.key == Key.Enter -> onActivate()
                event.key == Key.Tab -> onTabMove(if (event.isShiftPressed) -1 else 1)
                event.isAltPressed && event.key == Key.DirectionRight -> onTabMove(1)
                event.isAltPressed && event.key == Key.DirectionLeft -> onTabMove(-1)
                mod && event.key == Key.Backspace -> onClearText()
                event.key == Key.Escape -> onEscape()
                else -> return@onPreviewKeyEvent false
            }
            true
        },
    ) {
        CamoSearchField(
            query = query,
            onQueryChange = onQueryChange,
            onSearch = {},
            placeholder = placeholder,
            modifier = Modifier.fillMaxWidth().height(Graft.tokens.density.formControl).focusRequester(focus)
                .semantics { contentDescription = boxLabel },
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (tab in tabs) {
                CamoButton(
                    tab.label,
                    onClick = { onTab(tab.id) },
                    modifier = Modifier.height(Graft.tokens.density.control)
                        .semantics {
                            contentDescription = tab.spoken
                            this.selected = tab.selected
                        },
                    appearance = if (tab.selected) Appearance.Outline else Appearance.Ghost,
                )
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (emptyText != null && lines.none { it is Line.Result }) {
                CamoText(
                    emptyText,
                    style = Graft.tokens.dense,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
            LazyColumn(
                Modifier.fillMaxSize().semantics { contentDescription = listLabel },
                state = list,
            ) {
                itemsIndexed(lines) { _, line -> LineView(line, selected, onSelect) }
                if (hint != null) {
                    item {
                        CamoText(
                            hint,
                            style = Graft.tokens.dense,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
        CamoText(
            footer,
            style = Graft.tokens.dense,
            color = colors.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

private sealed interface Line {
    data class Header(val title: String) : Line

    data class Result(val row: SearchPaletteRow, val index: Int) : Line

    data object Loading : Line

    data class Failed(val message: String, val retryLabel: String, val retry: () -> Unit) : Line

    data class Note(val text: String) : Line
}

private fun lines(sections: List<SearchPaletteSection>): List<Line> {
    var index = 0
    return buildList {
        for (section in sections) {
            if (section.rows.isEmpty() && section.status == SearchPaletteStatus.Ready) continue
            add(Line.Header(section.title))
            for (row in section.rows) add(Line.Result(row, index++))
            when (val s = section.status) {
                SearchPaletteStatus.Ready -> Unit
                SearchPaletteStatus.Loading -> add(Line.Loading)
                is SearchPaletteStatus.Failed -> add(Line.Failed(s.message, s.retryLabel, s.retry))
            }
            section.note?.let { add(Line.Note(it)) }
        }
    }
}

@Composable
private fun LineView(line: Line, selected: Int, onSelect: (Int) -> Unit) {
    val colors = Camouflage.colors
    val density = Graft.tokens.density
    when (line) {
        is Line.Header -> CamoText(
            line.title.uppercase(),
            style = Graft.tokens.denseStrong,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp),
        )

        is Line.Result -> {
            val chosen = line.index == selected
            Row(
                Modifier.fillMaxWidth().height(density.row)
                    .background(if (chosen) colors.surfaceContainerHigh else Color.Transparent)
                    .clickable { onSelect(line.index) }
                    .semantics {
                        this.selected = chosen
                        contentDescription = line.row.spoken
                    }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BasicText(
                    marked(line.row.title, line.row.marks),
                    modifier = Modifier.weight(1f),
                    style = Graft.tokens.dense.copy(color = colors.onSurface),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                line.row.detail?.let {
                    CamoText(
                        it,
                        style = Graft.tokens.dense,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(260.dp),
                    )
                }
                line.row.trailing?.let {
                    CamoText(it, style = Graft.tokens.dense, color = colors.onSurfaceVariant, maxLines = 1)
                }
            }
        }

        Line.Loading -> Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            CamoSkeleton(10.dp, width = 220.dp)
        }

        is Line.Failed -> Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CamoText(line.message, style = Graft.tokens.dense, color = colors.error, modifier = Modifier.weight(1f))
            CamoButton(
                line.retryLabel,
                onClick = line.retry,
                modifier = Modifier.height(density.control),
                appearance = Appearance.Ghost,
            )
        }

        is Line.Note -> CamoText(
            line.text,
            style = Graft.tokens.dense,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** [text] with the characters at [marks] in bold and underlined, so the eye finds what matched. */
internal fun marked(text: String, marks: List<Int>): AnnotatedString = buildAnnotatedString {
    append(text)
    for (i in marks) {
        if (i in text.indices) {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline), i, i + 1)
        }
    }
}
