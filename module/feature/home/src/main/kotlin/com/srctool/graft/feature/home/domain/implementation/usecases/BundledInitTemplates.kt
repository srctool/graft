// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.domain.implementation.usecases

import com.srctool.graft.feature.home.domain.api.models.LicenseInfo
import com.srctool.graft.feature.home.domain.api.usecases.InitTemplates

/**
 * The templates under `graft/home/` in this module's resources (SOURCES.txt there says where they came from). The
 * lists are explicit: a jar can't be listed portably, and a test checks them against the files.
 */
internal object BundledInitTemplates : InitTemplates {
    override val gitignores: List<String> =
        listOf(
            "Android", "C", "C++", "Dart", "Flutter", "Go", "Gradle", "Java", "JetBrains", "Kotlin", "Linux", "macOS",
            "Node", "Python", "Rust", "Swift", "Terraform", "Unity", "VisualStudioCode", "Windows",
        )

    override val licenses: List<LicenseInfo> =
        listOf(
            LicenseInfo("MIT", "MIT License", needsHolder = true),
            LicenseInfo("Apache-2.0", "Apache License 2.0", needsHolder = false),
            LicenseInfo("GPL-3.0-or-later", "GNU GPL v3 or later", needsHolder = false),
            LicenseInfo("LGPL-3.0-or-later", "GNU LGPL v3 or later", needsHolder = false),
            LicenseInfo("AGPL-3.0-or-later", "GNU AGPL v3 or later", needsHolder = false),
            LicenseInfo("BSD-2-Clause", "BSD 2-Clause", needsHolder = true),
            LicenseInfo("BSD-3-Clause", "BSD 3-Clause", needsHolder = true),
            LicenseInfo("MPL-2.0", "Mozilla Public License 2.0", needsHolder = false),
            LicenseInfo("ISC", "ISC License", needsHolder = true),
            LicenseInfo("Unlicense", "The Unlicense", needsHolder = false),
        )

    override fun gitignore(name: String): String? =
        name.takeIf { it in gitignores }?.let { read("gitignore/$it.gitignore") }

    override fun license(id: String, year: Int, holder: String): String? {
        if (licenses.none { it.id == id }) return null
        return read("licenses/$id.txt")
            ?.replace("<year>", year.toString())
            ?.replace("<owner>", holder)
            ?.replace("<copyright holders>", holder)
    }

    private fun read(path: String): String? =
        BundledInitTemplates::class.java.classLoader.getResourceAsStream("graft/home/$path")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            // The files are verbatim copies; the repository they land in gets \n endings whatever they had.
            ?.replace("\r\n", "\n")
}
