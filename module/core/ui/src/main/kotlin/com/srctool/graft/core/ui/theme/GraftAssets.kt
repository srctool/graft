// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import com.srctool.camouflage.core.icon.IconSource

/**
 * The bundled fonts (UI Design, "Assets"): Camouflage skins never bundle fonts, so Graft does, the same on every OS.
 * Inter and JetBrains Mono are under the SIL Open Font License 1.1 (NOTICE).
 */
public object GraftFonts {
    /** The UI font: Inter, as in JetBrains IDEs (ADR-29). */
    public val sans: FontFamily =
        FontFamily(
            Font("graft/fonts/Inter-Regular.ttf", FontWeight.Normal),
            Font("graft/fonts/Inter-Medium.ttf", FontWeight.Medium),
            Font("graft/fonts/Inter-SemiBold.ttf", FontWeight.SemiBold),
        )

    /** Code and diffs, 13 px, ligatures off (they're off by default in Compose text). */
    public val mono: FontFamily =
        FontFamily(
            Font("graft/fonts/JetBrainsMono-Regular.ttf", FontWeight.Normal),
            Font("graft/fonts/JetBrainsMono-Bold.ttf", FontWeight.Bold),
        )
}

/**
 * The icons G1 draws: Carbon's (`@carbon/icons` 11.92.0, Apache-2.0), drawn on a 32 px grid and shown at 16 px in
 * panels and 20 px in the toolbar, plus git icons Carbon lacks, drawn on the same grid.
 */
public enum class GraftIcon(internal val file: String) {
    ADD("add"),
    ARCHIVE("archive"),
    BOT("bot"),
    BRANCH("branch"),
    CHECKMARK("checkmark"),
    CHEVRON_DOWN("chevron--down"),
    CHEVRON_RIGHT("chevron--right"),
    CLOSE("close"),
    CLOUD("cloud"),
    COMMIT("commit"),
    COMPARE("compare"),
    COPY("copy"),
    DOCUMENT("document"),
    DOWNLOAD("download"),
    ERROR("error"),
    FILTER("filter"),
    FOLDER("folder"),
    FOLDER_ADD("folder--add"),
    FOLDER_OPEN("folder--open"),
    FORK("fork"),
    HOME("home"),
    INFORMATION("information"),
    LAUNCH("launch"),
    LIST("list"),
    AI("machine-learning-model"),
    MENU("menu"),
    MERGE("merge"),
    OVERFLOW_MENU("overflow-menu--horizontal"),
    PIN("pin"),
    RECENTLY_VIEWED("recently-viewed"),
    RENEW("renew"),
    SEARCH("search"),
    SETTINGS("settings"),

    /** Drawn for Graft: Carbon has no stash icon. */
    STASH("stash"),
    SUBTRACT("subtract"),
    TAG("tag"),
    TIME("time"),
    TREE_VIEW("tree-view"),
    USER("user"),
    VIEW("view"),
    VIEW_OFF("view--off"),
    WARNING("warning"),
    ;

    internal val resource: String get() = "graft/icons/$file.svg"
}

/** [icon] as a painter, tinted by whoever draws it (Carbon's SVGs are single-color shapes). */
@Composable
public fun graftPainter(icon: GraftIcon): Painter {
    val density = LocalDensity.current
    return remember(icon, density) {
        val stream =
            GraftIcon::class.java.classLoader.getResourceAsStream(icon.resource)
                ?: error("Missing icon resource ${icon.resource}")
        stream.use { loadSvgPainter(it, density) }
    }
}

/** [icon] for Camouflage's components (`CamoIcon`, buttons, list items). */
@Composable
public fun graftIcon(icon: GraftIcon): IconSource =
    IconSource.Paint(graftPainter(icon), autoMirror = false, original = false)
