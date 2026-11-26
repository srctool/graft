// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.srctool.camouflage.core.theme.CamoColors
import com.srctool.camouflage.skin.fluent.FluentTokens

/**
 * The Fluent skin's state colors, built from Graft's palette (ADR-29): its renderers take button fills, hover and
 * press steps, strokes, focus and shadows from these rather than from the color roles, so Fluent's own blue and grays
 * would otherwise show. Every field is named, so a new one in Camouflage fails the build until Graft chooses it.
 */
internal fun graftFluentTokens(colors: CamoColors, dark: Boolean): FluentTokens {
    // Hover and press move away from the text drawn on the accent, so that text only gains contrast.
    val away = if (colors.onPrimary.isLight()) Color.Black else Color.White
    val accentHover = lerp(colors.primary, away, HOVER_STEP)
    val accentPressed = lerp(colors.primary, away, PRESSED_STEP)
    return FluentTokens(
        brandBackground = colors.primary,
        brandBackgroundHover = accentHover,
        brandBackgroundPressed = accentPressed,
        neutralForegroundOnBrand = colors.onPrimary,
        neutralBackground1Hover = colors.surfaceContainerHigh,
        neutralBackground1Pressed = colors.surfaceContainerHighest,
        subtleBackgroundHover = colors.surfaceContainerHigh,
        subtleBackgroundPressed = colors.surfaceContainerHighest,
        neutralForeground2Hover = colors.onSurface,
        neutralStroke1 = colors.outline,
        neutralStroke1Hover = lerp(colors.outline, colors.onSurface, HOVER_STEP),
        neutralStroke1Pressed = lerp(colors.outline, colors.onSurface, PRESSED_STEP),
        neutralStroke2 = colors.outlineVariant,
        brandForegroundLinkHover = accentLink(colors.primary, dark, HOVER_STEP),
        brandForegroundLinkPressed = accentLink(colors.primary, dark, PRESSED_STEP),
        strokeFocus1 = colors.surface,
        strokeFocus2 = colors.onSurface,
        shadowAmbient = Color.Black.copy(alpha = if (dark) 0.24f else 0.12f),
        shadowKey = Color.Black.copy(alpha = if (dark) 0.28f else 0.14f),
    )
}

// A link sits on the window, so it steps away from the window: darker on light, lighter on dark.
private fun accentLink(accent: Color, dark: Boolean, step: Float): Color =
    lerp(accent, if (dark) Color.White else Color.Black, step)

private fun Color.isLight(): Boolean = red + green + blue > LIGHT_SUM

private const val HOVER_STEP = 0.12f
private const val PRESSED_STEP = 0.24f
private const val LIGHT_SUM = 1.5f
