// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.srctool.camouflage.core.button.CamoButton
import com.srctool.camouflage.core.icon.CamoIcon
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.surface.CamoSurface
import com.srctool.camouflage.core.text.CamoText
import com.srctool.camouflage.core.theme.Appearance
import com.srctool.camouflage.core.theme.SurfaceLevel
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test

/**
 * The theme's look in one reference panel, per theme (Testing Strategy: goldens compared on Linux only). The panel
 * uses what later screens build on: the surface layers, text roles and the dense step, icons, buttons at Graft's
 * control height, status colors, the lane palette and the code font.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
class ThemeScreenshotTest {
    private fun capture(mode: GraftThemeMode) = runDesktopComposeUiTest(width = 360, height = 300) {
        setContent { GraftTheme(mode) { ReferencePanel() } }
        onRoot().captureRoboImage("src/test/snapshots/reference-panel-${mode.name.lowercase()}.png")
    }

    @Test
    fun `reference panel, light`() = capture(GraftThemeMode.LIGHT)

    @Test
    fun `reference panel, dark`() = capture(GraftThemeMode.DARK)

    @Test
    fun `reference panel, high contrast`() = capture(GraftThemeMode.HIGH_CONTRAST)
}

@Composable
private fun ReferencePanel() {
    val colors = Camouflage.colors
    val type = Camouflage.typography
    val tokens = Graft.tokens
    CamoSurface(modifier = Modifier.fillMaxWidth(), level = SurfaceLevel.Surface) {
        Row {
            CamoSurface(modifier = Modifier.width(150.dp).height(300.dp), level = SurfaceLevel.Lowest) {
                Column(Modifier.padding(8.dp)) {
                    CamoText("LOCAL", style = tokens.denseStrong, color = colors.onSurfaceVariant)
                    PanelRow(GraftIcon.BRANCH, "main", tokens.dense)
                    PanelRow(GraftIcon.BRANCH, "feature/login", tokens.dense)
                    CamoText("REMOTE", style = tokens.denseStrong, color = colors.onSurfaceVariant)
                    PanelRow(GraftIcon.CLOUD, "origin/main", tokens.dense)
                    PanelRow(GraftIcon.TAG, "v1.4", tokens.dense)
                    PanelRow(GraftIcon.STASH, "WIP on main", tokens.dense)
                }
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CamoText("Fix login timeout", style = type.titleMedium)
                CamoText("Ann Lee · 2 hours ago", style = tokens.dense, color = colors.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    tokens.lanes.colors.forEach {
                        Box(Modifier.size(tokens.density.commitDot).background(it, CircleShape))
                    }
                }
                CamoText("val timeout = 30", style = tokens.code)
                CamoText("Git couldn't reach the remote.", style = tokens.dense, color = colors.error)
                CamoButton("Open", onClick = {}, modifier = Modifier.height(tokens.density.control))
                CamoButton(
                    "Clone",
                    onClick = {},
                    modifier = Modifier.height(tokens.density.control),
                    appearance = Appearance.Outline,
                )
            }
        }
    }
}

@Composable
private fun PanelRow(icon: GraftIcon, label: String, style: androidx.compose.ui.text.TextStyle) {
    val density = Graft.tokens.density
    Row(Modifier.height(density.row), verticalAlignment = Alignment.CenterVertically) {
        CamoIcon(
            graftIcon(icon),
            contentDescription = null,
            size = density.icon,
            tint = Camouflage.colors.onSurfaceVariant,
        )
        CamoText(label, modifier = Modifier.padding(start = 6.dp), style = style)
    }
}
