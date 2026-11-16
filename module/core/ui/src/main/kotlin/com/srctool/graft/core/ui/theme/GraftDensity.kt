// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Graft's desktop sizes (UI Design, "Carbon at Graft's density"). Camouflage 0.1.0's Carbon skin draws every control
 * 48 tall and its component themes can't change a height, so Graft's components size Camouflage's with these
 * (Carbon's 48 minimum gives way to a tighter size) until the skin's density option ships (camouflage#70).
 */
public data class GraftDensity(
    /** Toolbars, panels and inline controls: Carbon's `xs`. */
    val control: Dp,
    /** Dialogs and forms: Carbon's `sm`. */
    val formControl: Dp,
    /** Rows in panels, trees and lists. */
    val row: Dp,
    val menuItem: Dp,
    /** Rows of the commit graph. */
    val graphRow: Dp,
    /** The graph's commit dot. */
    val commitDot: Dp,
    /** The graph's lane lines. */
    val laneWidth: Dp,
    /** Icons in panels and rows. */
    val icon: Dp,
    /** Icons in the toolbar. */
    val toolbarIcon: Dp,
) {
    public companion object {
        /** Settings → Density "standard": 24 px rows. */
        public val Standard: GraftDensity =
            GraftDensity(
                control = 24.dp,
                formControl = 32.dp,
                row = 24.dp,
                menuItem = 32.dp,
                graphRow = 26.dp,
                commitDot = 10.dp,
                laneWidth = 2.dp,
                icon = 16.dp,
                toolbarIcon = 20.dp,
            )

        /** Settings → Density "comfortable": 28 px rows, the graph 4 px taller to match. */
        public val Comfortable: GraftDensity = Standard.copy(row = 28.dp, graphRow = 30.dp)
    }
}
