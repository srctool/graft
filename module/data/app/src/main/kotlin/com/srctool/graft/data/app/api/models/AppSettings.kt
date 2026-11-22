// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.app.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * App-scope settings in `settings.json` v1 (Settings Reference). Profile and repository scopes arrive with profiles
 * (G3). A field added later needs a default, so older files still read.
 */
@Serializable
public data class AppSettings(
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val density: Density = Density.STANDARD,
    val diffStyle: DiffStyle = DiffStyle.UNIFIED,
    val ignoreWhitespace: Boolean = false,
    /** Bigger files aren't diffed inline (Settings Reference: 2 MB). */
    val inlineDiffLimitBytes: Long = 2L * 1024 * 1024,
    /** The welcome steps were finished or skipped; they open by themselves only until then (Onboarding). */
    val onboarded: Boolean = false,
    /** The default for repositories that don't choose (Settings Reference: Changes view). */
    val changesView: ChangesMode = ChangesMode.STAGING_AREA,
)

@Serializable
public enum class ThemeChoice {
    /** Graft Light or Dark, following the OS. */
    @SerialName("system")
    SYSTEM,

    @SerialName("light")
    LIGHT,

    @SerialName("dark")
    DARK,
}

@Serializable
public enum class Density {
    /** 24 px rows. */
    @SerialName("standard")
    STANDARD,

    /** 28 px rows. */
    @SerialName("comfortable")
    COMFORTABLE,
}

@Serializable
public enum class DiffStyle {
    @SerialName("unified")
    UNIFIED,

    @SerialName("side-by-side")
    SIDE_BY_SIDE,
}
