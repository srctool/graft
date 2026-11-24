// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.checkbox.CamoCheckbox
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.core.ui.theme.GraftSyntax
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap

/**
 * Where a [DiffViewer] is: its scroll position and the change block F7 and Shift+F7 last went to. Owned by the screen
 * so the position survives switching between unified and side by side.
 */
@Stable
public class DiffViewerState {
    internal val list: LazyListState = LazyListState()
    internal var starts: List<Int> = emptyList()
    internal var rows: List<DiffRow> = emptyList()

    /**
     * The row the checkbox keys act on (a hunk header or a changed line), as an index into the drawn rows, or -1.
     * Only moves while the viewer has a [DiffSelection]: Up and Down go to the next row that has a checkbox.
     */
    public var cursor: Int by mutableStateOf(-1)
        private set

    /** Up (-1) or Down (1): moves [cursor] to the next row with a checkbox; stays put at either end. */
    public suspend fun moveCursor(direction: Int) {
        var i = cursor + direction
        while (i in rows.indices && !rows[i].checkable()) i += direction
        if (i !in rows.indices) return
        cursor = i
        val visible = list.layoutInfo.visibleItemsInfo
        if (visible.isEmpty() || i <= visible.first().index || i >= visible.last().index) {
            list.scrollToItem(maxOf(i - 1, 0))
        }
    }

    /** The change block last jumped to, from 0, or -1 before the first jump. */
    public var currentChange: Int by mutableStateOf(-1)
        private set

    /** How many change blocks the diff has. */
    public val changeCount: Int get() = starts.size

    /** F7: scrolls to the next change block; stays on the last one. */
    public suspend fun nextChange() {
        if (starts.isEmpty()) return
        goTo(minOf(currentChange + 1, starts.lastIndex))
    }

    /** Shift+F7: scrolls to the previous change block; stays on the first one. */
    public suspend fun previousChange() {
        if (starts.isEmpty()) return
        goTo(maxOf(currentChange - 1, 0))
    }

    private suspend fun goTo(change: Int) {
        currentChange = change
        // One row of context above the change, as JetBrains does.
        list.scrollToItem(maxOf(starts[change] - 1, 0))
    }

    internal fun reset(rows: List<DiffRow>) {
        this.rows = rows
        if (cursor > rows.lastIndex) cursor = -1
        starts = changeStarts(rows)
        if (currentChange > starts.lastIndex) currentChange = -1
    }
}

private fun DiffRow.checkable(): Boolean = when (this) {
    is DiffRow.Header -> hunk >= 0
    is DiffRow.Unified -> ref != null
    is DiffRow.Split -> leftRef != null || rightRef != null
}

@Composable
public fun rememberDiffViewerState(): DiffViewerState = remember { DiffViewerState() }

/**
 * A file's diff (UI Design, "Diff viewer"; Accessibility, "Diffs"): [DiffMode.UNIFIED] or [DiffMode.SIDE_BY_SIDE],
 * with word-level highlights between paired lines, syntax colors from [highlighter], `+` / `−` gutter markers so a
 * change doesn't rest on color, and a label per line for screen readers ("Added line 12: …"). F7 and Shift+F7 jump
 * between changes while it has focus.
 *
 * With a [selection], each hunk header and each changed line gets a checkbox (the Changes panel stages by them; Local
 * History restores by them). `Up` and `Down` move [DiffViewerState.cursor] between them and `Space` toggles what it
 * is on; the viewer reports through [DiffSelection] and keeps no selection itself. Without one it looks as before.
 *
 * Long lines wrap when [softWrap] is on, so both sides of a split row stay aligned; off, they're cut at the edge.
 */
@Composable
public fun DiffViewer(
    document: DiffDocument,
    modifier: Modifier = Modifier,
    mode: DiffMode = DiffMode.UNIFIED,
    state: DiffViewerState = rememberDiffViewerState(),
    highlighter: Highlighter = Highlighter.Default,
    softWrap: Boolean = true,
    selection: DiffSelection? = null,
) {
    val rows = remember(document, mode) { diffRows(document, mode).also(state::reset) }
    val spans by produceState(emptySyntax, document, highlighter) {
        value = withContext(Dispatchers.Default) { highlightDocument(document, highlighter) }
    }
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = Camouflage.colors
    val focusColor = colors.primary
    val code = Graft.tokens.code
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val digits = document.hunks.maxOfOrNull { h -> h.lines.maxOf { maxOf(it.oldNumber ?: 0, it.newNumber ?: 0) } }
        ?.toString()?.length ?: 1
    val gutter = with(density) { measurer.measure("0".repeat(maxOf(digits, 2)), code).size.width.toDp() } + 12.dp
    val layout = DiffLayout(gutter, softWrap, spans, selection, state.cursor)

    LazyColumn(
        modifier
            .background(colors.surface)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.F7 -> {
                        scope.launch { if (event.isShiftPressed) state.previousChange() else state.nextChange() }
                        true
                    }

                    selection == null -> false

                    event.key == Key.DirectionDown -> {
                        scope.launch { state.moveCursor(1) }
                        true
                    }

                    event.key == Key.DirectionUp -> {
                        scope.launch { state.moveCursor(-1) }
                        true
                    }

                    event.key == Key.Spacebar -> state.rows.getOrNull(state.cursor)?.let { toggleRow(it, selection) } !=
                        null

                    else -> false
                }
            }.focusable(interactionSource = interaction)
            .drawWithContent {
                drawContent()
                // Carbon's inset focus border, inside the viewer so focus never moves the layout.
                if (focused) {
                    val w = 2.dp.toPx()
                    drawRect(
                        focusColor,
                        topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                        style = Stroke(w),
                    )
                }
            },
        state = state.list,
    ) {
        itemsIndexed(rows) { index, row ->
            val here = index == layout.cursor
            when (row) {
                is DiffRow.Header -> HeaderRow(row, document, layout, here)
                is DiffRow.Unified -> UnifiedRow(row, layout, here)
                is DiffRow.Split -> SplitRow(row, layout, here)
            }
        }
    }
}

private val emptySyntax: Map<DiffLineView, List<SyntaxSpan>> = emptyMap()

private class DiffLayout(
    val gutter: androidx.compose.ui.unit.Dp,
    val softWrap: Boolean,
    val spans: Map<DiffLineView, List<SyntaxSpan>>,
    val selection: DiffSelection? = null,
    val cursor: Int = -1,
)

private fun toggleRow(row: DiffRow, selection: DiffSelection) {
    when (row) {
        is DiffRow.Header -> if (row.hunk >= 0) selection.onToggleHunk(row.hunk)
        is DiffRow.Unified -> row.ref?.let(selection.onToggleLine)
        is DiffRow.Split -> row.refs.forEach(selection.onToggleLine)
    }
}

/** The look of the keyboard cursor on its row: a tint over the row's own color. */
@Composable
private fun Modifier.cursorMark(here: Boolean): Modifier =
    if (here) background(Camouflage.colors.primary.copy(alpha = 0.16f)) else this

/** A row's checkbox semantics: on when all its lines are ticked, and a click toggles them. */
private fun androidx.compose.ui.semantics.SemanticsPropertyReceiver.toggles(
    state: ToggleableState,
    label: String,
    toggle: () -> Unit,
) {
    toggleableState = state
    role = Role.Checkbox
    onClick(label) {
        toggle()
        true
    }
}

@Composable
private fun RowCheckbox(checked: Boolean, onChange: () -> Unit) {
    // The row carries the semantics; the box is for the pointer, 24 px wide like the other dense controls.
    // One code line high: Carbon's checkbox is 48 px unless its parent is tighter, which would stretch every row.
    Box(
        Modifier.size(width = 24.dp, height = 20.dp).clearAndSetSemantics {
        },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        CamoCheckbox(checked, onCheckedChange = { onChange() })
    }
}

/**
 * Highlights each hunk's two sides as whole texts (old: context and removed lines; new: context and added lines), so a
 * construct spanning lines colors all of them, and maps the spans back to each line object.
 */
private fun highlightDocument(document: DiffDocument, highlighter: Highlighter): Map<DiffLineView, List<SyntaxSpan>> {
    val result = IdentityHashMap<DiffLineView, List<SyntaxSpan>>()
    for (hunk in document.hunks) {
        for (side in listOf(DiffLineKind.ADDED, DiffLineKind.REMOVED)) {
            // The new side drops removed lines and the old side added ones; context lines take the new side's spans.
            val lines = hunk.lines.filter { it.kind != side }
            if (lines.isEmpty()) continue
            val text = lines.joinToString("\n") { it.text }
            val perLine = splitSpans(lines.map { it.text }, highlighter.highlight(text, document.path))
            lines.forEachIndexed { i, line ->
                if (line.kind != DiffLineKind.CONTEXT || side == DiffLineKind.REMOVED) result[line] = perLine[i]
            }
        }
    }
    return result
}

@Composable
private fun HeaderRow(row: DiffRow.Header, document: DiffDocument, layout: DiffLayout, here: Boolean) {
    val colors = Camouflage.colors
    val selection = layout.selection
    val text = row.text
    val check = if (selection != null && row.hunk >= 0) document.checkOf(row.hunk, selection.selected) else null
    val partial = graftText("ui.diff.partlySelected", "partly selected")
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerLow)
            .cursorMark(here)
            .padding(start = if (selection != null) 0.dp else 8.dp, end = 8.dp)
            .clearAndSetSemantics {
                heading()
                contentDescription = text
                if (check != null && selection != null) {
                    toggles(
                        when (check) {
                            DiffCheck.NONE -> ToggleableState.Off
                            DiffCheck.PARTIAL -> ToggleableState.Indeterminate
                            DiffCheck.ALL -> ToggleableState.On
                        },
                        text,
                    ) { selection.onToggleHunk(row.hunk) }
                    if (check == DiffCheck.PARTIAL) stateDescription = partial
                }
            },
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        if (check != null && selection != null) {
            RowCheckbox(check == DiffCheck.ALL) { selection.onToggleHunk(row.hunk) }
        }
        BasicText(
            text,
            modifier = Modifier.weight(1f).padding(vertical = 2.dp),
            style = Graft.tokens.code.copy(color = colors.onSurfaceVariant),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UnifiedRow(row: DiffRow.Unified, layout: DiffLayout, here: Boolean) {
    val line = row.line
    val description = lineDescription(line, line.kind != DiffLineKind.REMOVED)
    val selection = layout.selection
    val ref = row.ref
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(lineBackground(line.kind))
            .cursorMark(here)
            .clearAndSetSemantics {
                contentDescription = description
                if (selection != null && ref != null) {
                    toggles(if (ref in selection.selected) ToggleableState.On else ToggleableState.Off, description) {
                        selection.onToggleLine(ref)
                    }
                }
            },
    ) {
        if (selection != null) {
            if (ref != null) {
                RowCheckbox(ref in selection.selected) { selection.onToggleLine(ref) }
            } else {
                Spacer(Modifier.width(24.dp))
            }
        }
        Number(line.oldNumber, layout)
        Number(line.newNumber, layout)
        LineCode(line, row.words, layout)
    }
}

@Composable
private fun SplitRow(row: DiffRow.Split, layout: DiffLayout, here: Boolean) {
    val colors = Camouflage.colors
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).cursorMark(here)) {
        SplitCell(row.left, row.leftWords, newSide = false, row.leftRef, layout)
        Box(Modifier.width(1.dp).fillMaxHeight().background(colors.outlineVariant))
        SplitCell(row.right, row.rightWords, newSide = true, row.rightRef, layout)
    }
}

@Composable
private fun RowScope.SplitCell(
    line: DiffLineView?,
    words: List<IntRange>,
    newSide: Boolean,
    ref: DiffLineRef?,
    layout: DiffLayout,
) {
    val colors = Camouflage.colors
    if (line == null) {
        // The other side has a line here that this side doesn't.
        Box(Modifier.weight(1f).fillMaxHeight().background(colors.surfaceContainerLow).clearAndSetSemantics {})
        return
    }
    val description = lineDescription(line, newSide)
    val selection = layout.selection
    Row(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(lineBackground(line.kind))
            .clearAndSetSemantics {
                contentDescription = description
                if (selection != null && ref != null) {
                    toggles(if (ref in selection.selected) ToggleableState.On else ToggleableState.Off, description) {
                        selection.onToggleLine(ref)
                    }
                }
            },
    ) {
        if (selection != null) {
            if (ref != null) {
                RowCheckbox(ref in selection.selected) { selection.onToggleLine(ref) }
            } else {
                Spacer(Modifier.width(24.dp))
            }
        }
        Number(if (newSide) line.newNumber else line.oldNumber, layout)
        LineCode(line, words, layout)
    }
}

@Composable
private fun Number(number: Int?, layout: DiffLayout) {
    val colors = Camouflage.colors
    BasicText(
        number?.toString().orEmpty(),
        modifier = Modifier.width(layout.gutter).padding(end = 6.dp),
        style = Graft.tokens.code.copy(
            color = colors.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        ),
        maxLines = 1,
    )
}

@Composable
private fun RowScope.LineCode(line: DiffLineView, words: List<IntRange>, layout: DiffLayout) {
    val colors = Camouflage.colors
    val (marker, markerColor) =
        when (line.kind) {
            DiffLineKind.ADDED -> "+" to colors.onSuccessContainer
            DiffLineKind.REMOVED -> "−" to colors.onErrorContainer
            DiffLineKind.CONTEXT -> " " to colors.onSurfaceVariant
        }
    BasicText(
        marker,
        modifier = Modifier.width(16.dp).padding(start = 4.dp),
        style = Graft.tokens.code.copy(color = markerColor),
        maxLines = 1,
    )
    val syntax = Graft.tokens.syntax
    val wordBackground = wordBackground(line.kind)
    val text =
        remember(line, words, layout.spans, syntax, wordBackground) {
            annotate(line.text, layout.spans[line].orEmpty(), syntax, words, wordBackground)
        }
    BasicText(
        text,
        modifier = Modifier.weight(1f).heightIn(min = 20.dp).padding(end = 8.dp),
        style = Graft.tokens.code.copy(color = colors.onSurface),
        softWrap = layout.softWrap,
        maxLines = if (layout.softWrap) Int.MAX_VALUE else 1,
        overflow = TextOverflow.Clip,
    )
}

private fun annotate(
    text: String,
    spans: List<SyntaxSpan>,
    syntax: GraftSyntax,
    words: List<IntRange>,
    wordBackground: Color,
): AnnotatedString = buildAnnotatedString {
    append(text)
    for (span in spans) {
        if (span.start >= text.length) continue
        val style =
            when (span.kind) {
                SyntaxKind.KEYWORD -> SpanStyle(color = syntax.keyword)
                SyntaxKind.STRING -> SpanStyle(color = syntax.string)
                SyntaxKind.NUMBER -> SpanStyle(color = syntax.number)
                SyntaxKind.COMMENT -> SpanStyle(color = syntax.comment, fontStyle = FontStyle.Italic)
                SyntaxKind.ANNOTATION -> SpanStyle(color = syntax.annotation)
                SyntaxKind.OPERATOR -> SpanStyle(color = syntax.operator)
            }
        addStyle(style, span.start, minOf(span.end, text.length))
    }
    for (range in words) {
        if (range.first >= text.length) continue
        addStyle(SpanStyle(background = wordBackground), range.first, minOf(range.last + 1, text.length))
    }
}

@Composable
private fun lineBackground(kind: DiffLineKind): Color {
    val colors = Camouflage.colors
    return when (kind) {
        DiffLineKind.ADDED -> colors.successContainer
        DiffLineKind.REMOVED -> colors.errorContainer
        DiffLineKind.CONTEXT -> colors.surface
    }
}

@Composable
private fun wordBackground(kind: DiffLineKind): Color {
    val colors = Camouflage.colors
    return when (kind) {
        DiffLineKind.ADDED -> colors.success.copy(alpha = 0.3f).compositeOver(colors.successContainer)
        DiffLineKind.REMOVED -> colors.error.copy(alpha = 0.3f).compositeOver(colors.errorContainer)
        DiffLineKind.CONTEXT -> Color.Unspecified
    }
}

/** "Added line 12: …", "Removed line 9: …", "Line 4: …", numbered on the side the line is read on. */
@Composable
private fun lineDescription(line: DiffLineView, newSide: Boolean): String {
    val number = (if (newSide) line.newNumber else line.oldNumber) ?: line.newNumber ?: line.oldNumber ?: 0
    val args = arrayOf("line" to number, "text" to line.text)
    return when (line.kind) {
        DiffLineKind.ADDED -> graftText("ui.diff.added", "Added line {line}: {text}", *args)
        DiffLineKind.REMOVED -> graftText("ui.diff.removed", "Removed line {line}: {text}", *args)
        DiffLineKind.CONTEXT -> graftText("ui.diff.context", "Line {line}: {text}", *args)
    }
}
