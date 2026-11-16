// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.provider.Camouflage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

/**
 * Desktop scrollbars in the theme's colors (camouflage#78): a 4 px bar that widens to 8 px under the mouse, shown
 * while the content scrolls or the bar is hovered or dragged, and faded out a second after. Place it over the
 * scrolling content's edge, so it costs no layout space.
 */
@Composable
public fun GraftVerticalScrollbar(adapter: ScrollbarAdapter, modifier: Modifier = Modifier) {
    OverlayScrollbar(adapter) { style, interaction, visible ->
        VerticalScrollbar(adapter, modifier.alpha(visible), style = style, interactionSource = interaction)
    }
}

/** The horizontal [GraftVerticalScrollbar]. */
@Composable
public fun GraftHorizontalScrollbar(adapter: ScrollbarAdapter, modifier: Modifier = Modifier) {
    OverlayScrollbar(adapter) { style, interaction, visible ->
        HorizontalScrollbar(adapter, modifier.alpha(visible), style = style, interactionSource = interaction)
    }
}

@Composable
private fun OverlayScrollbar(
    adapter: ScrollbarAdapter,
    bar: @Composable (ScrollbarStyle, MutableInteractionSource, Float) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val dragged by interaction.collectIsDraggedAsState()
    var scrolling by remember { mutableStateOf(false) }
    LaunchedEffect(adapter) {
        snapshotFlow { adapter.scrollOffset }.drop(1).collectLatest {
            scrolling = true
            delay(FADE_AFTER_MS)
            scrolling = false
        }
    }
    val visible by animateFloatAsState(if (hovered || dragged || scrolling) 1f else 0f, label = "scrollbar")
    val wide = hovered || dragged
    val colors = Camouflage.colors
    val style =
        ScrollbarStyle(
            minimalHeight = 24.dp,
            thickness = if (wide) 8.dp else 4.dp,
            shape = RoundedCornerShape(4.dp),
            hoverDurationMillis = 150,
            unhoverColor = colors.outline.copy(alpha = 0.6f),
            hoverColor = colors.outline,
        )
    bar(style, interaction, visible)
}

private const val FADE_AFTER_MS = 1_000L
