// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.icon.CamoIcon
import com.srctool.camouflage.core.iconbutton.CamoIconButton
import com.srctool.camouflage.core.menu.CamoContextMenuArea
import com.srctool.camouflage.core.menu.CamoMenuAnchor
import com.srctool.camouflage.core.menu.CamoMenuEntry
import com.srctool.camouflage.core.menu.CamoMenuItem
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.text.CamoText
import com.srctool.camouflage.core.theme.Appearance
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.core.ui.theme.GraftIcon
import com.srctool.graft.core.ui.theme.graftIcon
import kotlin.math.abs

/** One open document: a repository tab, a diff tab. [modified] shows a dot; a [pinned] tab can't be closed or moved. */
public data class DocumentTab(
    val id: String,
    val title: String,
    val icon: GraftIcon? = null,
    val closable: Boolean = true,
    val pinned: Boolean = false,
    val modified: Boolean = false,
)

/**
 * Editor-style tabs (camouflage#74): Carbon's compact line tabs with an accent underline, a close button on the
 * selected and the hovered tab, middle-click to close, reorder by dragging or `Mod+Shift+←/→`, and a list-all menu
 * when they overflow. Arrows move between tabs, Enter or Space selects. [contextMenu] gives each tab's right-click
 * entries (close others, close to the right); the app owns the order and the selection.
 */
@Composable
public fun DocumentTabs(
    tabs: List<DocumentTab>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    contextMenu: (DocumentTab) -> List<CamoMenuEntry> = { emptyList() },
) {
    val scroll = rememberScrollState()
    val widths = remember { mutableStateMapOf<String, Int>() }
    val height = Graft.tokens.density.formControl
    Row(modifier.fillMaxWidth().height(height).background(Camouflage.colors.surfaceContainerLowest)) {
        Row(Modifier.weight(1f).horizontalScroll(scroll)) {
            tabs.forEachIndexed { index, tab ->
                CamoContextMenuArea(contextMenu(tab)) {
                    Tab(
                        tab = tab,
                        selected = tab.id == selectedId,
                        onSelect = { onSelect(tab.id) },
                        onClose = { onClose(tab.id) },
                        onMove = { by ->
                            val to = (index + by).coerceIn(0, tabs.lastIndex)
                            if (to != index && !tab.pinned && !tabs[to].pinned) onMove(index, to)
                        },
                        neighbourWidth = { by -> tabs.getOrNull(index + by)?.let { widths[it.id] } },
                        modifier = Modifier.onSizeChanged { widths[tab.id] = it.width },
                    )
                }
            }
        }
        if (scroll.maxValue > 0) {
            val items = tabs.map { tab ->
                CamoMenuItem(
                    tab.title,
                    onClick = { onSelect(tab.id) },
                    checked =
                    tab.id == selectedId,
                )
            }
            val label = graftText("ui.tabs.all", "Show all tabs")
            CamoMenuAnchor(items = items) { open ->
                CamoIconButton(
                    icon = { CamoIcon(graftIcon(GraftIcon.CHEVRON_DOWN), contentDescription = null) },
                    contentDescription = label,
                    onClick = open,
                    modifier = Modifier.size(height),
                    appearance = Appearance.Ghost,
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Tab(
    tab: DocumentTab,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onMove: (Int) -> Unit,
    neighbourWidth: (Int) -> Int?,
    modifier: Modifier,
) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val focusManager = LocalFocusManager.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val closeLabel = graftText("ui.tabs.close", "Close {title}", "title" to tab.title)
    val modifiedLabel = graftText("ui.tabs.modified", "Changed")
    val currentMove by rememberUpdatedState(onMove)
    var dragged by remember { mutableFloatStateOf(0f) }
    val underline = colors.primary
    val focusRing = colors.primary
    Row(
        modifier
            .fillMaxHeight()
            .background(
                if (selected) {
                    colors.surface
                } else if (hovered) {
                    colors.surfaceContainerLow
                } else {
                    colors.surfaceContainerLowest
                },
            )
            .drawBehind {
                // Carbon's line tab: a 2 px accent line under the selected tab; keyboard focus is the inset border.
                if (selected) {
                    drawRect(
                        underline,
                        topLeft = Offset(0f, size.height - 2.dp.toPx()),
                        size = size.copy(height = 2.dp.toPx()),
                    )
                }
                if (focused) {
                    val w = 2.dp.toPx()
                    drawRect(focusRing, size = size, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
                }
            }.hoverable(interaction)
            .onPointerEvent(PointerEventType.Press) { event ->
                when (event.button) {
                    PointerButton.Primary -> onSelect()
                    PointerButton.Tertiary -> if (tab.closable && !tab.pinned) onClose()
                    else -> Unit
                }
            }.pointerInput(tab.id) {
                detectHorizontalDragGestures(onDragEnd = {
                    dragged = 0f
                }, onDragCancel = { dragged = 0f }) { change, amount ->
                    change.consume()
                    dragged += amount
                    // Past half the neighbour's width, the tab takes its place.
                    val by = if (dragged > 0) 1 else -1
                    val width = neighbourWidth(by) ?: return@detectHorizontalDragGestures
                    if (abs(dragged) > width / 2f) {
                        currentMove(by)
                        dragged -= by * width
                    }
                }
            }.onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val mod = event.isCtrlPressed || event.isMetaPressed
                when {
                    mod && event.isShiftPressed && event.key == Key.DirectionLeft -> onMove(-1)
                    mod && event.isShiftPressed && event.key == Key.DirectionRight -> onMove(1)
                    event.key == Key.DirectionLeft -> focusManager.moveFocus(FocusDirection.Left)
                    event.key == Key.DirectionRight -> focusManager.moveFocus(FocusDirection.Right)
                    event.key == Key.Enter || event.key == Key.Spacebar -> onSelect()
                    else -> return@onKeyEvent false
                }
                true
            }.focusable(interactionSource = interaction)
            .semantics(mergeDescendants = true) {
                role = Role.Tab
                this.selected = selected
                contentDescription = tab.title
                if (tab.modified) stateDescription = modifiedLabel
                onClick {
                    onSelect()
                    true
                }
            }.padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val content = if (selected) colors.onSurface else colors.onSurfaceVariant
        if (tab.pinned) {
            CamoIcon(
                graftIcon(GraftIcon.PIN),
                contentDescription = null,
                modifier = Modifier.size(tokens.density.icon),
                tint = content,
            )
        }
        tab.icon?.let {
            CamoIcon(
                graftIcon(it),
                contentDescription = null,
                modifier = Modifier.size(tokens.density.icon),
                tint = content,
            )
        }
        CamoText(
            tab.title,
            modifier = Modifier.padding(start = 6.dp),
            style = if (selected) tokens.denseStrong else tokens.dense,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (tab.modified) Box(Modifier.padding(start = 6.dp).size(6.dp).background(colors.primary, CircleShape))
        val showClose = tab.closable && !tab.pinned && (selected || hovered || focused)
        Box(Modifier.padding(start = 4.dp).size(tokens.density.control - 4.dp), contentAlignment = Alignment.Center) {
            if (showClose) {
                CamoIconButton(
                    icon = {
                        CamoIcon(graftIcon(GraftIcon.CLOSE), contentDescription = null, modifier = Modifier.size(12.dp))
                    },
                    contentDescription = closeLabel,
                    onClick = onClose,
                    // Out of the focus order: it shows while its tab has focus, and keyboard closing is Mod+W.
                    modifier = Modifier.size(tokens.density.control - 4.dp).focusProperties { canFocus = false },
                    appearance = Appearance.Ghost,
                )
            }
        }
    }
}
