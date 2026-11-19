// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.about

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
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test
import kotlin.test.assertEquals

/** About (wireframe S40), with its labels and keys. */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
class AboutPageTest {
    private fun ui(block: ComposeUiTest.() -> Unit) = runDesktopComposeUiTest(820, 600) { block() }

    private fun ComposeUiTest.page(
        state: AboutState,
        mode: GraftThemeMode = GraftThemeMode.LIGHT,
        onEvent: (AboutEvent) -> Unit = {},
        onClose: () -> Unit = {},
    ) = setContent { GraftTheme(mode) { AboutContent(state, onEvent, onClose) } }

    private fun ComposeUiTest.snapshot(name: String) = onRoot().captureRoboImage("src/test/snapshots/$name.png")

    private val entries =
        listOf(
            LicenseEntry(
                "Carbon icons",
                "11.92.0",
                "Apache License 2.0",
                "https://www.apache.org/licenses/LICENSE-2.0",
            ),
            LicenseEntry("Compose Multiplatform", "1.12.2", "Apache License, Version 2.0", null),
            LicenseEntry("IBM Plex Sans", null, "SIL Open Font License 1.1", "https://openfontlicense.org"),
            LicenseEntry("example:no-license", "1", "", null),
        )
    private val ready =
        AboutState(
            version = "0.1.0",
            git = "2.54.0",
            system = SystemInfo("21.0.12", "macOS 27.0 (aarch64)"),
            licenses = entries,
            loaded = true,
        )

    @Test
    fun `the list and its rows are read whole`() = ui {
        page(ready)
        onNodeWithContentDescription("Third-party licenses, 4").assertExists()
        onNode(hasContentDescription("Compose Multiplatform, 1.12.2, Apache License, Version 2.0")).assertExists()
        onNode(hasContentDescription("IBM Plex Sans, SIL Open Font License 1.1")).assertExists()
        onNodeWithText("Git 2.54.0 · JDK 21.0.12 · macOS 27.0 (aarch64)").assertExists()
    }

    @Test
    fun `the arrows move in the list, Esc closes, and Copy asks for the version info`() = ui {
        val events = mutableStateListOf<AboutEvent>()
        var closed = 0
        page(ready, onEvent = { events += it }, onClose = { closed++ })
        val list = onNodeWithContentDescription("Third-party licenses, 4")
        list.requestFocus()
        list.performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionUp)
            pressKey(Key.Escape)
        }
        onNodeWithText("Copy version info").performClick()
        assertEquals(
            listOf(AboutEvent.Moved(1), AboutEvent.Moved(-1), AboutEvent.CopyVersionInfo),
            events.toList(),
        )
        assertEquals(1, closed)
    }

    @Test
    fun `screenshot, about, light`() = ui {
        page(ready)
        snapshot("about-light")
    }

    @Test
    fun `screenshot, about, filtered, dark`() = ui {
        page(ready.copy(query = "apache", selected = 1), GraftThemeMode.DARK)
        snapshot("about-filtered-dark")
    }

    @Test
    fun `screenshot, about, no git`() = ui {
        page(ready.copy(git = null))
        snapshot("about-no-git-light")
    }
}
