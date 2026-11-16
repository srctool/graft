// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.menu.CamoContextMenuArea
import com.srctool.camouflage.core.menu.CamoMenuItem
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.text.CamoText
import com.srctool.graft.core.ui.theme.Graft
import java.awt.Cursor

/**
 * A column of a desktop table: the graph's Branch/Tag, Graph, Message, Author, Date, SHA (UI Design, "Center"). The
 * [fill] column takes the width the others leave and can't be resized itself.
 */
@Immutable
public data class TableColumn(
    val id: String,
    val title: String,
    val defaultWidth: Dp,
    val minWidth: Dp = 40.dp,
    val hideable: Boolean = true,
    val fill: Boolean = false,
)

/** Column widths and visibility, owned by the app so they're saved (camouflage#72). Reordering comes later. */
@Stable
public class TableColumnsState(public val columns: List<TableColumn>) {
    private val widths = mutableStateMapOf<String, Dp>()
    private val hidden = mutableStateListOf<String>()

    init {
        require(columns.count { it.fill } <= 1) { "at most one column fills the rest" }
    }

    public val visibleColumns: List<TableColumn> get() = columns.filter { it.id !in hidden }

    public fun width(id: String): Dp = widths[id] ?: column(id).defaultWidth

    public fun isVisible(id: String): Boolean = id !in hidden

    public fun resizeBy(id: String, delta: Dp) {
        val column = column(id)
        if (column.fill) return
        widths[id] = maxOf(column.minWidth, width(id) + delta)
    }

    public fun reset(id: String) {
        widths.remove(id)
    }

    /** Hides or shows [id]; a column that isn't hideable stays, and the last visible column can't go. */
    public fun setVisible(id: String, visible: Boolean) {
        if (visible) {
            hidden.remove(id)
        } else if (column(id).hideable && visibleColumns.size > 1 && id !in hidden) {
            hidden.add(id)
        }
    }

    public fun snapshot(): Map<String, Float?> = columns.associate {
        it.id to
            if (it.id in hidden) null else width(it.id).value
    }

    /** Restores what [snapshot] saved: a null width is a hidden column. Unknown ids are ignored. */
    public fun restore(saved: Map<String, Float?>) {
        for ((id, width) in saved) {
            if (columns.none { it.id == id }) continue
            if (width == null) setVisible(id, false) else widths[id] = maxOf(column(id).minWidth, width.dp)
        }
    }

    private fun column(id: String): TableColumn = columns.first { it.id == id }
}

/** [content] in [column]'s place in a table row: its width, or the rest of the row for the fill column. */
@Composable
public fun RowScope.TableCell(state: TableColumnsState, column: TableColumn, content: @Composable () -> Unit) {
    val modifier = if (column.fill) Modifier.weight(1f) else Modifier.width(state.width(column.id))
    Box(modifier.fillMaxHeight().padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) { content() }
}

/**
 * The header row (camouflage#72): drag a header's right edge to resize (double-click it for the default width),
 * right-click for the menu that hides and shows columns, `Alt+←/→` on a focused header to resize by keyboard.
 */
@Composable
public fun TableHeader(state: TableColumnsState, modifier: Modifier = Modifier) {
    val colors = Camouflage.colors
    val menu =
        state.columns.filter { it.hideable }.map { column ->
            CamoMenuItem(
                column.title,
                onClick = { state.setVisible(column.id, !state.isVisible(column.id)) },
                checked = state.isVisible(column.id),
            )
        }
    CamoContextMenuArea(menu) {
        Row(modifier.fillMaxWidth().height(Graft.tokens.density.row).background(colors.surfaceContainerLow)) {
            state.visibleColumns.forEach { column -> HeaderCell(state, column) }
        }
    }
}

@Composable
private fun RowScope.HeaderCell(state: TableColumnsState, column: TableColumn) {
    val colors = Camouflage.colors
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusColor = colors.primary
    val step = 8.dp
    val sized = if (column.fill) Modifier.weight(1f) else Modifier.width(state.width(column.id))
    Box(
        sized
            .fillMaxHeight()
            .drawBehind {
                if (focused) drawRect(focusColor, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !event.isAltPressed || column.fill) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> state.resizeBy(column.id, step)
                    Key.DirectionLeft -> state.resizeBy(column.id, -step)
                    else -> return@onKeyEvent false
                }
                true
            }.focusable(interactionSource = interaction)
            .semantics(mergeDescendants = true) {
                heading()
                role = Role.Button
                if (!column.fill) {
                    customActions =
                        listOf(
                            CustomAccessibilityAction("Wider") {
                                state.resizeBy(column.id, step)
                                true
                            },
                            CustomAccessibilityAction("Narrower") {
                                state.resizeBy(column.id, -step)
                                true
                            },
                        )
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        CamoText(
            column.title,
            modifier = Modifier.padding(horizontal = 6.dp),
            style = Graft.tokens.denseStrong,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!column.fill) {
            val line = colors.outlineVariant
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(6.dp)
                    .fillMaxHeight()
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                    .pointerInput(column.id) {
                        detectHorizontalDragGestures { change, amount ->
                            change.consume()
                            state.resizeBy(column.id, with(density) { amount.toDp() })
                        }
                    }.pointerInput(column.id) { detectTapGestures(onDoubleTap = { state.reset(column.id) }) }
                    .drawBehind {
                        drawLine(
                            line,
                            Offset(size.width - 1f, 4.dp.toPx()),
                            Offset(
                                size.width - 1f,
                                size.height - 4.dp.toPx(),
                            ),
                        )
                    },
            )
        }
    }
}
