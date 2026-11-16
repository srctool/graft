// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.srctool.camouflage.core.theme.CamoColors

/**
 * Atom's One Dark and One Light as Camouflage color roles (UI Design, "Atom One colors"), and a high-contrast set.
 *
 * The published values are kept where they pass WCAG 2.2 AA; where a color carries text and doesn't, a lighter (dark)
 * or darker (light) step of the same hue is used. One gives four surfaces (window, panels, raised layer, selection);
 * Camouflage's six surface roles are filled with steps between them. Every value here passes Camouflage's theme
 * validator, which `GraftThemeTest` runs on all three.
 */
internal object GraftColors {
    private val darkText = Color(0xFF1B1F25)

    val dark: CamoColors =
        CamoColors(
            primary = Color(0xFF61AFEF),
            onPrimary = darkText,
            primaryContainer = Color(0xFF25384D),
            onPrimaryContainer = Color(0xFF8CC6F6),
            secondary = Color(0xFFC678DD),
            onSecondary = darkText,
            secondaryContainer = Color(0xFF3D2C47),
            onSecondaryContainer = Color(0xFFDCABE9),
            tertiary = Color(0xFF56B6C2),
            onTertiary = darkText,
            tertiaryContainer = Color(0xFF233C40),
            onTertiaryContainer = Color(0xFF8BD0D8),
            // Window, then panels below it and layers above it.
            surface = Color(0xFF282C34),
            surfaceContainerLowest = Color(0xFF21252B),
            surfaceContainerLow = Color(0xFF2C313A),
            surfaceContainer = Color(0xFF2F343E),
            surfaceContainerHigh = Color(0xFF333842),
            surfaceContainerHighest = Color(0xFF373C47),
            onSurface = Color(0xFFABB2BF),
            // The comment gray #5c6370 fails AA as text.
            onSurfaceVariant = Color(0xFFA0A8B6),
            outline = Color(0xFF737985),
            outlineVariant = Color(0xFF3E4451),
            error = Color(0xFFE06C75),
            onError = darkText,
            errorContainer = Color(0xFF4A2A2E),
            onErrorContainer = Color(0xFFF0A5AB),
            success = Color(0xFF98C379),
            onSuccess = darkText,
            successContainer = Color(0xFF2D3D28),
            onSuccessContainer = Color(0xFFB9D8A3),
            warning = Color(0xFFE5C07B),
            onWarning = darkText,
            warningContainer = Color(0xFF45392A),
            onWarningContainer = Color(0xFFF0D7A6),
            info = Color(0xFF56B6C2),
            onInfo = darkText,
            infoContainer = Color(0xFF233C40),
            onInfoContainer = Color(0xFF8BD0D8),
            inverseSurface = Color(0xFFABB2BF),
            inverseOnSurface = Color(0xFF282C34),
            inversePrimary = Color(0xFF2F5FD0),
            scrim = Color(0x99000000),
        )

    val light: CamoColors =
        CamoColors(
            // One Light's #4078f2 is 3.9:1 on the background; a darker step carries text and the button.
            primary = Color(0xFF2F5FD0),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDDE7FD),
            onPrimaryContainer = Color(0xFF1E45A6),
            secondary = Color(0xFFA626A4),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFF5DFF5),
            onSecondaryContainer = Color(0xFF7D1C7B),
            tertiary = Color(0xFF0170A0),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFD9EEF7),
            onTertiaryContainer = Color(0xFF01577C),
            // Window, then One Light's panels (darker, like One Dark's) and its raised layer.
            surface = Color(0xFFFAFAFA),
            surfaceContainerLowest = Color(0xFFEAEAEB),
            surfaceContainerLow = Color(0xFFF0F0F1),
            surfaceContainer = Color(0xFFEAEAEB),
            surfaceContainerHigh = Color(0xFFE5E5E6),
            surfaceContainerHighest = Color(0xFFDFDFE0),
            onSurface = Color(0xFF383A42),
            // One Light's #a0a1a7 fails AA as text.
            onSurfaceVariant = Color(0xFF5D5F66),
            outline = Color(0xFF8A8B91),
            outlineVariant = Color(0xFFDBDBDC),
            error = Color(0xFFC23A2F),
            onError = Color.White,
            errorContainer = Color(0xFFFBE3E1),
            onErrorContainer = Color(0xFF9A2B22),
            success = Color(0xFF3A7A39),
            onSuccess = Color.White,
            successContainer = Color(0xFFE1F0E1),
            onSuccessContainer = Color(0xFF2C5E2B),
            warning = Color(0xFF7A5401),
            onWarning = Color.White,
            warningContainer = Color(0xFFF6EAD2),
            onWarningContainer = Color(0xFF634400),
            info = Color(0xFF0170A0),
            onInfo = Color.White,
            infoContainer = Color(0xFFD9EEF7),
            onInfoContainer = Color(0xFF01577C),
            inverseSurface = Color(0xFF383A42),
            inverseOnSurface = Color(0xFFFAFAFA),
            inversePrimary = Color(0xFF61AFEF),
            scrim = Color(0x66000000),
        )

    /** Black and white with saturated light accents, far above AA (Accessibility, "High contrast"). */
    val highContrast: CamoColors =
        CamoColors(
            primary = Color(0xFF8CCBFF),
            onPrimary = Color.Black,
            primaryContainer = Color(0xFF00335C),
            onPrimaryContainer = Color.White,
            secondary = Color(0xFFF0B3FF),
            onSecondary = Color.Black,
            secondaryContainer = Color(0xFF4A1157),
            onSecondaryContainer = Color.White,
            tertiary = Color(0xFF7FE3EF),
            onTertiary = Color.Black,
            tertiaryContainer = Color(0xFF003C42),
            onTertiaryContainer = Color.White,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0D0D0D),
            surfaceContainer = Color(0xFF141414),
            surfaceContainerHigh = Color(0xFF1A1A1A),
            surfaceContainerHighest = Color(0xFF242424),
            onSurface = Color.White,
            onSurfaceVariant = Color(0xFFE6E6E6),
            outline = Color.White,
            outlineVariant = Color(0xFFB3B3B3),
            error = Color(0xFFFF9AA2),
            onError = Color.Black,
            errorContainer = Color(0xFF5C0010),
            onErrorContainer = Color.White,
            success = Color(0xFFA8F08A),
            onSuccess = Color.Black,
            successContainer = Color(0xFF103D00),
            onSuccessContainer = Color.White,
            warning = Color(0xFFFFE08A),
            onWarning = Color.Black,
            warningContainer = Color(0xFF4A3500),
            onWarningContainer = Color.White,
            info = Color(0xFF7FE3EF),
            onInfo = Color.Black,
            infoContainer = Color(0xFF003C42),
            onInfoContainer = Color.White,
            inverseSurface = Color.White,
            inverseOnSurface = Color.Black,
            inversePrimary = Color(0xFF00335C),
            scrim = Color(0xCC000000),
        )
}

/**
 * The graph's eight lane colors from Atom One (UI Design, "Graph style"): blue, purple, green, red, orange, yellow,
 * cyan and the text gray, each at least 3:1 on the theme's window background. Lanes are always paired with labels and
 * marker shapes, never told apart by color alone (Accessibility).
 */
public class GraftLanes internal constructor(public val colors: List<Color>) {
    /** The color of lane color index [index] (the layout's `color`), cycling through the eight. */
    public operator fun get(index: Int): Color = colors[Math.floorMod(index, colors.size)]

    internal companion object {
        val dark =
            GraftLanes(
                listOf(0xFF61AFEF, 0xFFC678DD, 0xFF98C379, 0xFFE06C75, 0xFFD19A66, 0xFFE5C07B, 0xFF56B6C2, 0xFFABB2BF)
                    .map { Color(it) },
            )

        // One Light's yellow #c18401 is 2.9:1 on the background; #a87300 is the darker step.
        val light =
            GraftLanes(
                listOf(0xFF4078F2, 0xFFA626A4, 0xFF50A14F, 0xFFE45649, 0xFF986801, 0xFFA87300, 0xFF0184BC, 0xFF696C77)
                    .map { Color(it) },
            )

        val highContrast =
            GraftLanes(
                listOf(0xFF8CCBFF, 0xFFF0B3FF, 0xFFA8F08A, 0xFFFF9AA2, 0xFFFFB86C, 0xFFFFE08A, 0xFF7FE3EF, 0xFFFFFFFF)
                    .map { Color(it) },
            )
    }
}

/**
 * Atom One's syntax colors for code in diffs (UI Design: "the diff's syntax highlighting (One's syntax colors)"), with a
 * lighter (dark) or darker (light) step where the published color fails AA as text: One Dark's comment gray `#5c6370`,
 * One Light's green, yellow, cyan and comment gray.
 */
@Immutable
public data class GraftSyntax(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val annotation: Color,
    val operator: Color,
) {
    internal companion object {
        val dark =
            GraftSyntax(
                keyword = Color(0xFFC678DD),
                string = Color(0xFF98C379),
                number = Color(0xFFD19A66),
                comment = Color(0xFF8B919D),
                annotation = Color(0xFFE5C07B),
                operator = Color(0xFF56B6C2),
            )
        val light =
            GraftSyntax(
                keyword = Color(0xFFA626A4),
                string = Color(0xFF3A7A39),
                number = Color(0xFF986801),
                comment = Color(0xFF696C77),
                annotation = Color(0xFF7A5401),
                operator = Color(0xFF0170A0),
            )
        val highContrast =
            GraftSyntax(
                keyword = Color(0xFFF0B3FF),
                string = Color(0xFFA8F08A),
                number = Color(0xFFFFB86C),
                comment = Color(0xFFE6E6E6),
                annotation = Color(0xFFFFE08A),
                operator = Color(0xFF7FE3EF),
            )
    }
}
