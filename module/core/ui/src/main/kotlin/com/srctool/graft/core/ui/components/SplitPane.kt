// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.graft.core.ui.l10n.graftText
import java.awt.Cursor

/** Which pane of a [SplitPane] has the size; the other takes what's left. */
public enum class SizedPane { FIRST, SECOND }

/**
 * A [SplitPane]'s divider position, owned by the app so it can be saved and restored (camouflage#71). [size] is the
 * sized pane's width or height, kept between [minSize] and [maxSize]; [collapsed] hides that pane without losing it.
 */
@Stable
public class SplitPaneState(
    initialSize: Dp,
    public val minSize: Dp = 80.dp,
    public val maxSize: Dp = Dp.Infinity,
    initiallyCollapsed: Boolean = false,
) {
    private val defaultSize = initialSize.coerceIn(minSize, maxSize)

    public var size: Dp by mutableStateOf(defaultSize)
        private set
    public var collapsed: Boolean by mutableStateOf(initiallyCollapsed)
        private set

    public fun resize(to: Dp) {
        size = to.coerceIn(minSize, maxSize)
        collapsed = false
    }

    public fun resizeBy(delta: Dp): Unit = resize(size + delta)

    public fun toggleCollapsed() {
        collapsed = !collapsed
    }

    /** The size it started with, shown again (double-click on the divider). */
    public fun reset() {
        size = defaultSize
        collapsed = false
    }

    private companion object {
        private fun Dp.coerceIn(min: Dp, max: Dp): Dp = if (this < min) {
            min
        } else if (this > max) {
            max
        } else {
            this
        }
    }
}

/**
 * Two panes with a divider the user drags or moves by keyboard (camouflage#71): arrows resize by 16 px (64 with Shift),
 * Enter collapses or restores the sized pane, a double-click restores its first size. Nest two to get three panes.
 * The divider is Carbon's 1 px `border-subtle` line, the accent while hovered, dragged or focused, on a 7 px target
 * that overlaps the panes so it costs no layout space.
 */
@Composable
public fun SplitPane(
    state: SplitPaneState,
    modifier: Modifier = Modifier,
    orientation: Orientation = Orientation.Horizontal,
    sizedPane: SizedPane = SizedPane.FIRST,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    val horizontal = orientation == Orientation.Horizontal
    BoxWithConstraints(modifier) {
        val available = if (horizontal) maxWidth else maxHeight
        val sized = if (state.collapsed) 0.dp else minOf(state.size, available)
        val firstSize = if (sizedPane == SizedPane.FIRST) sized else available - sized
        if (horizontal) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(firstSize).fillMaxHeight()) { if (firstSize > 0.dp) first() }
                Box(Modifier.width(available - firstSize).fillMaxHeight()) { if (available > firstSize) second() }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.height(firstSize).fillMaxWidth()) { if (firstSize > 0.dp) first() }
                Box(Modifier.height(available - firstSize).fillMaxWidth()) { if (available > firstSize) second() }
            }
        }
        Divider(state, horizontal, sizedPane, firstSize)
    }
}

@Composable
private fun Divider(state: SplitPaneState, horizontal: Boolean, sizedPane: SizedPane, at: Dp) {
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    var dragging by remember { mutableStateOf(false) }
    val colors = Camouflage.colors
    val active = hovered || focused || dragging
    // Dragging toward the sized pane shrinks it.
    val sign = if (sizedPane == SizedPane.FIRST) 1 else -1
    val label = graftText("ui.split.resize", "Resize")
    val step = 16.dp
    val target = 7.dp
    val cursor = PointerIcon(Cursor(if (horizontal) Cursor.E_RESIZE_CURSOR else Cursor.N_RESIZE_CURSOR))
    val placement =
        if (horizontal) {
            Modifier.offset(x = at - target / 2).width(target).fillMaxHeight()
        } else {
            Modifier.offset(y = at - target / 2).height(target).fillMaxWidth()
        }
    Box(
        placement
            .zIndex(1f)
            .hoverable(interaction)
            .pointerHoverIcon(cursor)
            .pointerInput(state, horizontal, sign) {
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, amount ->
                    change.consume()
                    val delta = with(density) { (if (horizontal) amount.x else amount.y).toDp() }
                    state.resizeBy(delta * sign)
                }
            }.pointerInput(state) { detectTapGestures(onDoubleTap = { state.reset() }) }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val amount = if (event.isShiftPressed) step * 4 else step
                val grow = if (horizontal) Key.DirectionRight else Key.DirectionDown
                val shrink = if (horizontal) Key.DirectionLeft else Key.DirectionUp
                when (event.key) {
                    grow -> state.resizeBy(amount * sign)
                    shrink -> state.resizeBy(-amount * sign)
                    Key.Enter, Key.NumPadEnter -> state.toggleCollapsed()
                    else -> return@onKeyEvent false
                }
                true
            }.focusable(interactionSource = interaction)
            .semantics {
                contentDescription = label
                progressBarRangeInfo =
                    ProgressBarRangeInfo(state.size.value, state.minSize.value..state.maxSize.value.coerceAtMost(1e5f))
                if (state.collapsed) stateDescription = "collapsed"
                setProgress { value ->
                    state.resize(value.dp)
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val thickness = if (active) 2.dp else 1.dp
        val color = if (active) colors.primary else colors.outlineVariant
        Box(
            (if (horizontal) Modifier.width(thickness).fillMaxHeight() else Modifier.height(thickness).fillMaxWidth())
                .background(color),
        )
    }
}
