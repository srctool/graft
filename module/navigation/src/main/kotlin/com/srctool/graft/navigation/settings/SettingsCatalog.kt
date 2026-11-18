// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.settings

import androidx.compose.runtime.Composable
import com.srctool.graft.core.ui.l10n.graftText

/** A setting's row, by the text key its label is looked up with. */
internal enum class SettingRow(val key: String, val fallback: String, val page: SettingsPage) {
    Theme("settings.theme", "Theme", SettingsPage.Appearance),
    Density("settings.density", "Density", SettingsPage.Appearance),
    DiffStyle("settings.diffStyle", "Diff style", SettingsPage.Diffs),
    IgnoreWhitespace("settings.ignoreWhitespace", "Ignore whitespace changes", SettingsPage.Diffs),
    InlineLimit("settings.inlineLimit", "Inline diff size limit", SettingsPage.Diffs),
    Welcome("settings.welcome", "Run the welcome steps again…", SettingsPage.Onboarding),
}

internal val SettingsPage.titleKey: String
    get() = when (this) {
        SettingsPage.Appearance -> "settings.page.appearance"
        SettingsPage.Diffs -> "settings.page.diffs"
        SettingsPage.Onboarding -> "settings.page.onboarding"
    }

internal val SettingsGroup.titleKey: String
    get() = when (this) {
        SettingsGroup.General -> "settings.group.general"
        SettingsGroup.WorkingCopy -> "settings.group.workingCopy"
        SettingsGroup.Onboarding -> "settings.group.onboarding"
    }

/** Every label the tree and pages show, looked up once so search matches what's on screen (in any language). */
@Composable
internal fun settingsLabels(): Map<String, String> = mapOf(
    "settings.page.appearance" to graftText("settings.page.appearance", "Appearance"),
    "settings.page.diffs" to graftText("settings.page.diffs", "Diffs"),
    "settings.page.onboarding" to graftText("settings.page.onboarding", "Welcome"),
    "settings.group.general" to graftText("settings.group.general", "General"),
    "settings.group.workingCopy" to graftText("settings.group.workingCopy", "Working copy"),
    "settings.group.onboarding" to graftText("settings.group.onboarding", "Onboarding"),
) + SettingRow.entries.associate { it.key to graftText(it.key, it.fallback) }

/** A row matches a search by its label (or its page's or group's), ignoring case. */
internal fun rowMatches(row: SettingRow, query: String, label: (String) -> String): Boolean {
    val q = query.trim()
    return q.isEmpty() || label(row.key).contains(q, ignoreCase = true)
}

/** The pages a search leaves: those whose title, group or any row matches. An empty search leaves all of them. */
internal fun visiblePages(query: String, label: (String) -> String): List<SettingsPage> {
    val q = query.trim()
    if (q.isEmpty()) return SettingsPage.entries
    return SettingsPage.entries.filter { page ->
        label(page.titleKey).contains(q, ignoreCase = true) ||
            label(page.group.titleKey).contains(q, ignoreCase = true) ||
            SettingRow.entries.any { it.page == page && rowMatches(it, q, label) }
    }
}
