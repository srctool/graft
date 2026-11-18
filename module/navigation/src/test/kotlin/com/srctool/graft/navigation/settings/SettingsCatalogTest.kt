// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsCatalogTest {
    private val labels =
        mapOf(
            "settings.page.appearance" to "Appearance",
            "settings.page.diffs" to "Diffs",
            "settings.page.onboarding" to "Welcome",
            "settings.group.general" to "General",
            "settings.group.workingCopy" to "Working copy",
            "settings.group.onboarding" to "Onboarding",
            "settings.theme" to "Theme",
            "settings.density" to "Density",
            "settings.diffStyle" to "Diff style",
            "settings.ignoreWhitespace" to "Ignore whitespace changes",
            "settings.inlineLimit" to "Inline diff size limit",
            "settings.welcome" to "Run the welcome steps again…",
        )
    private val label: (String) -> String = { labels.getValue(it) }

    @Test
    fun `no search leaves every page, in the tree's order`() {
        assertEquals(SettingsPage.entries, visiblePages("", label))
        assertEquals(SettingsPage.entries, visiblePages("   ", label))
    }

    @Test
    fun `a search keeps the pages with a matching title, group or row, ignoring case`() {
        assertEquals(listOf(SettingsPage.Appearance), visiblePages("THEME", label))
        assertEquals(listOf(SettingsPage.Diffs), visiblePages("whitespace", label))
        assertEquals(listOf(SettingsPage.Diffs), visiblePages("working", label))
        assertEquals(listOf(SettingsPage.Onboarding), visiblePages("again", label))
        assertEquals(emptyList(), visiblePages("keymap", label))
    }

    @Test
    fun `a row matches by its own label only`() {
        assertTrue(rowMatches(SettingRow.InlineLimit, "size", label))
        assertTrue(!rowMatches(SettingRow.Theme, "size", label))
        assertTrue(rowMatches(SettingRow.Theme, "", label))
    }

    @Test
    fun `every row belongs to a page and every page has a row`() {
        assertEquals(SettingsPage.entries.toSet(), SettingRow.entries.map { it.page }.toSet())
    }
}
