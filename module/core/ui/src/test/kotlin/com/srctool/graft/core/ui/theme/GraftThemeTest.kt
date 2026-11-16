// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.theme

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.srctool.graft.core.ui.l10n.GraftLocalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraftThemeTest {
    private val modes = listOf(GraftThemeMode.LIGHT, GraftThemeMode.DARK, GraftThemeMode.HIGH_CONTRAST)

    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance() + 0.05
        val l2 = b.luminance() + 0.05
        return max(l1, l2).toDouble() / min(l1, l2)
    }

    @Test
    fun `every theme passes Camouflage's validator`() {
        for (mode in modes) assertEquals(emptyList(), graftTheme(mode).validate(), "$mode")
    }

    @Test
    fun `system follows the OS appearance`() {
        assertEquals(GraftColors.dark, graftTheme(GraftThemeMode.SYSTEM, systemDark = true).colors)
        assertEquals(GraftColors.light, graftTheme(GraftThemeMode.SYSTEM, systemDark = false).colors)
    }

    @Test
    fun `Carbon's square shapes and IBM Plex Sans`() {
        val theme = graftTheme(GraftThemeMode.LIGHT)
        val probe = Size(100f, 100f)
        assertEquals(0f, theme.shapes.medium.topStart.toPx(probe, Density(1f)))
        assertEquals(GraftFonts.sans, theme.typography.bodyMedium.fontFamily)
    }

    @Test
    fun `lane colors stand out from the window in every theme`() {
        for (mode in modes) {
            val background = graftTheme(mode).colors.surface
            graftTokens(mode, GraftDensity.Standard).lanes.colors.forEachIndexed { i, lane ->
                val ratio = contrast(lane, background)
                assertTrue(ratio >= 3.0, "$mode lane $i: ${"%.2f".format(ratio)}:1")
            }
        }
    }

    @Test
    fun `the densities match the UI Design note`() {
        with(GraftDensity.Standard) {
            assertEquals(listOf(24.dp, 32.dp, 24.dp, 26.dp), listOf(control, formControl, row, graphRow))
        }
        assertEquals(28.dp, GraftDensity.Comfortable.row)
    }

    @Test
    fun `every icon and font is bundled and loads`() {
        val loader = GraftIcon::class.java.classLoader
        for (icon in GraftIcon.entries) {
            val painter = assertNotNull(loader.getResourceAsStream(icon.resource), icon.name).use {
                loadSvgPainter(it, Density(1f))
            }
            // Carbon's SVGs give only a viewBox, so the painter is square at Skia's default size and scales to any size.
            val size = painter.intrinsicSize
            assertTrue(size.width > 0f && size.width == size.height, "${icon.name}: $size")
        }
        val files =
            listOf(
                "IBMPlexSans-Regular",
                "IBMPlexSans-Medium",
                "IBMPlexSans-SemiBold",
                "JetBrainsMono-Regular",
                "JetBrainsMono-Bold",
            )
        files.forEach { assertNotNull(loader.getResource("graft/fonts/$it.ttf"), it) }
        listOf("IBMPlexSans-OFL.txt", "JetBrainsMono-OFL.txt", "CarbonIcons-Apache-2.0.txt").forEach {
            assertNotNull(loader.getResource("graft/licenses/$it"), it)
        }
    }

    @Test
    fun `texts exist in every language with the same placeholders`() {
        val english = GraftLocalizer.load("graft/l10n/graft.properties")
        val placeholders = Regex("""\{\w+}""")
        for (language in GraftLocalizer.languages) {
            val translated = GraftLocalizer.load(GraftLocalizer.languageFile(Locale.forLanguageTag(language)))
            assertEquals(english.keys, translated.keys, language)
            for ((key, text) in english) {
                assertEquals(
                    placeholders.findAll(text).map { it.value }.toSet(),
                    placeholders.findAll(translated.getValue(key)).map { it.value }.toSet(),
                    "$language $key",
                )
            }
        }
    }

    @Test
    fun `the localizer fills arguments and falls back to English`() {
        val indonesian = GraftLocalizer(Locale.forLanguageTag("id"))
        assertEquals(
            "Git berhenti dengan kode keluar 128.",
            indonesian.resolve("git.error.exit", mapOf("exitCode" to 128)),
        )
        val french = GraftLocalizer(Locale.FRENCH)
        assertEquals("Git stopped with exit code 1.", french.resolve("git.error.exit", mapOf("exitCode" to 1)))
        assertEquals(
            "Git {found} is too old; Graft needs Git 2.38 or newer.",
            GraftLocalizer.format(
                "Git {found} is too old; Graft needs Git {required} or newer.",
                mapOf("required" to "2.38"),
            ),
        )
        assertNull(french.resolve("no.such.key", emptyMap()))
    }
}
