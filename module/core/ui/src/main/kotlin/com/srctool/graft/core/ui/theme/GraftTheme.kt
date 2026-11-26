// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.srctool.camouflage.core.provider.CamouflageProvider
import com.srctool.camouflage.core.theme.CamoTheme
import com.srctool.camouflage.skin.fluent.FluentDensity
import com.srctool.camouflage.skin.fluent.FluentSkin
import com.srctool.camouflage.skin.fluent.fluentTheme
import com.srctool.graft.core.ui.l10n.GraftLocalizer
import com.srctool.sinew.exception.Localizer
import com.srctool.sinew.l10n.rememberSinewLocalizer

/** Graft's themes (UI Design, "Assets"). [SYSTEM] follows the OS's light or dark appearance. */
public enum class GraftThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    HIGH_CONTRAST,
}

/** What Graft's own components read beyond Camouflage's theme: the density, the lane colors, the extra type steps. */
@Immutable
public data class GraftTokens(
    val density: GraftDensity,
    val lanes: GraftLanes,
    /** Syntax colors for code in diffs. */
    val syntax: GraftSyntax,
    /** The 13 px step between the caption and body sizes, for dense panels and the graph (UI Design). */
    val dense: TextStyle,
    val denseStrong: TextStyle,
    /** Code and diffs. */
    val code: TextStyle,
)

private val LocalGraftTokens = staticCompositionLocalOf<GraftTokens> { error("GraftTheme isn't set up") }
private val LocalGraftLocalizer = staticCompositionLocalOf<Localizer> { error("GraftTheme isn't set up") }

/** Graft's tokens, inside [GraftTheme]. */
public object Graft {
    public val tokens: GraftTokens
        @Composable get() = LocalGraftTokens.current

    /** Graft's texts, then Sinew's, for messages and labels. */
    public val localizer: Localizer
        @Composable get() = LocalGraftLocalizer.current
}

/** The Camouflage theme for [mode]: Fluent's type ramp, shapes and motion in Inter, with Atom One's colors. */
public fun graftTheme(mode: GraftThemeMode, systemDark: Boolean = false): CamoTheme {
    val resolved = mode.resolve(systemDark)
    val colors =
        when (resolved) {
            GraftThemeMode.LIGHT -> GraftColors.light
            GraftThemeMode.HIGH_CONTRAST -> GraftColors.highContrast
            else -> GraftColors.dark
        }
    val dark = resolved != GraftThemeMode.LIGHT
    return fluentTheme(dark = dark, density = FluentDensity.Medium, fontFamily = GraftFonts.sans)
        .copy(colors = colors, tokenSets = listOf(graftFluentTokens(colors, dark)))
}

internal fun GraftThemeMode.resolve(systemDark: Boolean): GraftThemeMode = when (this) {
    GraftThemeMode.SYSTEM -> if (systemDark) GraftThemeMode.DARK else GraftThemeMode.LIGHT
    else -> this
}

internal fun graftTokens(mode: GraftThemeMode, density: GraftDensity): GraftTokens {
    val lanes =
        when (mode) {
            GraftThemeMode.LIGHT -> GraftLanes.light
            GraftThemeMode.HIGH_CONTRAST -> GraftLanes.highContrast
            else -> GraftLanes.dark
        }
    val syntax =
        when (mode) {
            GraftThemeMode.LIGHT -> GraftSyntax.light
            GraftThemeMode.HIGH_CONTRAST -> GraftSyntax.highContrast
            else -> GraftSyntax.dark
        }
    val dense = TextStyle(fontFamily = GraftFonts.sans, fontSize = 13.sp, lineHeight = 18.sp)
    return GraftTokens(
        density = density,
        lanes = lanes,
        syntax = syntax,
        dense = dense,
        denseStrong = dense.copy(fontWeight = FontWeight.SemiBold),
        code = TextStyle(fontFamily = GraftFonts.mono, fontSize = 13.sp, lineHeight = 20.sp),
    )
}

/**
 * Graft's look around [content]: Camouflage with the Fluent skin and Graft's theme, Graft's tokens, and the
 * localizer. `:app:desktop` sets it up once per window; screenshot tests set it up per case.
 */
@Composable
public fun GraftTheme(
    mode: GraftThemeMode = GraftThemeMode.SYSTEM,
    density: GraftDensity = GraftDensity.Standard,
    localizer: Localizer? = null,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val resolved = mode.resolve(systemDark)
    val theme = remember(resolved) { graftTheme(resolved) }
    val tokens = remember(resolved, density) { graftTokens(resolved, density) }
    val appStrings = localizer ?: remember { GraftLocalizer() }
    val strings = rememberSinewLocalizer(appStrings)
    CamouflageProvider(FluentSkin, theme) {
        CompositionLocalProvider(
            LocalGraftTokens provides tokens,
            LocalGraftLocalizer provides strings,
            content = content,
        )
    }
}
