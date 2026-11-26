// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.about

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One third-party component About lists: its [name], [version] (null for an asset), [license] and where to read it. */
internal data class LicenseEntry(val name: String, val version: String?, val license: String, val url: String?)

/** Where About gets the license list from; the app's is the build's report, tests pass their own. */
internal fun interface LicenseSource {
    fun load(): List<LicenseEntry>
}

/**
 * The build's license report (`graft/licenses/third-party.json`, written by the license report plugin) plus the assets
 * Graft bundles, which `NOTICE` credits. A report that's missing or unreadable leaves just the assets.
 */
internal class BundledLicenseSource(
    private val report: () -> String? = {
        BundledLicenseSource::class.java.getResourceAsStream("/graft/licenses/third-party.json")?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    },
) : LicenseSource {
    override fun load(): List<LicenseEntry> = (parse(report()) + assets).sortedBy { it.name.lowercase() }

    internal companion object {
        /** The bundled fonts, icons, colors and templates (`NOTICE`). */
        val assets: List<LicenseEntry> =
            listOf(
                LicenseEntry("Inter", "4.1", "SIL Open Font License 1.1", "https://openfontlicense.org"),
                LicenseEntry("JetBrains Mono", null, "SIL Open Font License 1.1", "https://openfontlicense.org"),
                LicenseEntry(
                    "Carbon icons",
                    "11.92.0",
                    "Apache License 2.0",
                    "https://www.apache.org/licenses/LICENSE-2.0",
                ),
                LicenseEntry("Atom One colors", null, "MIT License", "https://opensource.org/licenses/MIT"),
                LicenseEntry(
                    ".gitignore templates (github/gitignore)",
                    null,
                    "CC0 1.0",
                    "https://creativecommons.org/publicdomain/zero/1.0/",
                ),
                LicenseEntry(
                    "License texts (SPDX License List)",
                    null,
                    "As each license",
                    "https://spdx.org/licenses/",
                ),
            )

        /** Reads the plugin's JSON; anything that isn't in its shape gives an empty list. */
        fun parse(json: String?): List<LicenseEntry> {
            if (json.isNullOrBlank()) return emptyList()
            val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return emptyList()
            val dependencies = (root["dependencies"] as? JsonArray) ?: return emptyList()
            return dependencies.mapNotNull { element ->
                val dependency = element as? JsonObject ?: return@mapNotNull null
                val name = dependency["moduleName"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                // Graft's own modules are the program, not a third party.
                if (name.startsWith("com.srctool.graft")) return@mapNotNull null
                val license = (dependency["moduleLicenses"] as? JsonArray)?.firstOrNull() as? JsonObject
                LicenseEntry(
                    name = name,
                    version = dependency["moduleVersion"]?.jsonPrimitive?.contentOrNull,
                    license = license?.get("moduleLicense")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    url = license?.get("moduleLicenseUrl")?.jsonPrimitive?.contentOrNull,
                )
            }.distinctBy { it.name to it.version }
        }
    }
}

/** What a bug report needs about the machine, and never a path, a token or a user name. */
internal data class SystemInfo(val jdk: String, val os: String) {
    companion object {
        fun current(): SystemInfo = SystemInfo(
            jdk = System.getProperty("java.version").orEmpty(),
            os = "${System.getProperty("os.name").orEmpty()} ${System.getProperty("os.version").orEmpty()} " +
                "(${System.getProperty("os.arch").orEmpty()})",
        )
    }
}

/** Graft's own version, from the `graft/build.properties` the app build writes. */
internal fun interface BuildVersion {
    fun version(): String
}

internal class BundledBuildVersion : BuildVersion {
    override fun version(): String = runCatching {
        val props = java.util.Properties()
        BundledBuildVersion::class.java.getResourceAsStream("/graft/build.properties")?.use { props.load(it) }
        props.getProperty("version")
    }.getOrNull().orEmpty().ifBlank { "development" }
}
