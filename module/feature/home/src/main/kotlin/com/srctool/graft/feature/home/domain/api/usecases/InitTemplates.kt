// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.domain.api.usecases

import com.srctool.graft.feature.home.domain.api.models.LicenseInfo

/** The .gitignore templates and licenses Init offers (bundled; nothing is fetched at run time). */
internal interface InitTemplates {
    /** Template names, such as `Kotlin` or `macOS`, in the order the menu shows them. */
    val gitignores: List<String>

    val licenses: List<LicenseInfo>

    /** The template's text with \n line endings, or null for a name that isn't bundled. */
    fun gitignore(name: String): String?

    /** The license's text with its year and holder filled in, or null for an id that isn't bundled. */
    fun license(id: String, year: Int, holder: String): String?
}
