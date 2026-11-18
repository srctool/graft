// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.settings

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.srctool.graft.core.ui.theme.GraftTheme
import com.srctool.graft.core.ui.theme.GraftThemeMode
import com.srctool.graft.data.app.api.models.AppSettings
import com.srctool.graft.data.app.api.models.ThemeChoice
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test
import kotlin.test.assertEquals

/** Settings per page and state (wireframe S37), with its labels and keys. */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
class SettingsPageTest {
    private fun ui(block: ComposeUiTest.() -> Unit) = runDesktopComposeUiTest(900, 600) { block() }

    private fun ComposeUiTest.page(
        state: SettingsState,
        mode: GraftThemeMode = GraftThemeMode.LIGHT,
        onEvent: (SettingsEvent) -> Unit = {},
        onClose: () -> Unit = {},
    ) = setContent { GraftTheme(mode) { SettingsContent(state, onEvent, onClose) } }

    private fun ComposeUiTest.snapshot(name: String) = onRoot().captureRoboImage("src/test/snapshots/$name.png")

    private val ready = SettingsState(loaded = true)

    @Test
    fun `each row reads as its label and its value, and the tree lists the pages`() = ui {
        page(ready)
        onNode(hasContentDescription("Theme, System")).assertExists()
        onNode(hasContentDescription("Density, Standard")).assertExists()
        onNodeWithContentDescription("Settings, tree").assertExists()
        onNodeWithContentDescription("Diffs").assertExists()
    }

    @Test
    fun `choosing a page and a setting sends the events`() = ui {
        val events = mutableStateListOf<SettingsEvent>()
        page(ready, onEvent = { events += it })
        onNodeWithContentDescription("Diffs").performClick()
        onNodeWithText("Run the welcome steps again…").assertDoesNotExist()
        assertEquals(listOf<SettingsEvent>(SettingsEvent.PageChosen(SettingsPage.Diffs)), events.toList())
    }

    @Test
    fun `the arrows move through the tree and Esc closes`() = ui {
        val events = mutableStateListOf<SettingsEvent>()
        var closed = 0
        page(ready, onEvent = { events += it }, onClose = { closed++ })
        val tree = onNodeWithContentDescription("Settings, tree")
        tree.requestFocus()
        tree.performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.Escape)
        }
        assertEquals(listOf<SettingsEvent>(SettingsEvent.PageChosen(SettingsPage.Diffs)), events.toList())
        assertEquals(1, closed)
    }

    @Test
    fun `a search with no match says so`() = ui {
        page(ready.copy(query = "keymap"))
        onNodeWithText("No settings match \"keymap\".").assertExists()
    }

    @Test
    fun `screenshot, appearance, light`() = ui {
        page(ready)
        snapshot("settings-appearance-light")
    }

    @Test
    fun `screenshot, diffs with a bad limit, dark`() = ui {
        page(
            ready.copy(page = SettingsPage.Diffs, limitText = "two", settings = AppSettings(ignoreWhitespace = true)),
            GraftThemeMode.DARK,
        )
        snapshot("settings-diffs-dark")
    }

    @Test
    fun `screenshot, a search marks the matching rows`() = ui {
        page(ready.copy(query = "diff", page = SettingsPage.Diffs, limitText = "2"))
        snapshot("settings-search-light")
    }

    @Test
    fun `screenshot, a newer file is read-only`() = ui {
        page(ready.copy(readOnly = true, settings = AppSettings(theme = ThemeChoice.DARK)))
        snapshot("settings-readonly-light")
    }

    @Test
    fun `screenshot, the welcome page`() = ui {
        page(ready.copy(page = SettingsPage.Onboarding))
        snapshot("settings-welcome-light")
    }
}
