// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.presentation.clone

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.srctool.graft.core.ui.theme.GraftTheme
import com.srctool.graft.core.ui.theme.GraftThemeMode
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test

/** Clone per state (wireframe S2). */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
class ClonePageTest {
    private fun ui(block: ComposeUiTest.() -> Unit) = runDesktopComposeUiTest(700, 640) { block() }

    private fun ComposeUiTest.page(state: CloneState, mode: GraftThemeMode = GraftThemeMode.LIGHT) =
        setContent { GraftTheme(mode) { CloneDialogContent(state, {}, {}) } }

    private fun ComposeUiTest.snapshot(name: String) = onRoot().captureRoboImage("src/test/snapshots/$name.png")

    private val filled =
        CloneState(
            url = "https://github.com/srctool/graft.git",
            folder = "/work/graft",
            folderStatus = FolderStatus.Free,
        )

    @Test
    fun `screenshot, clone, idle`() = ui {
        page(filled)
        snapshot("clone-idle-light")
    }

    @Test
    fun `screenshot, clone with options, dark`() = ui {
        page(filled.copy(optionsOpen = true, shallow = true, singleBranch = true, branch = "main"), GraftThemeMode.DARK)
        snapshot("clone-options-dark")
    }

    @Test
    fun `screenshot, clone, running`() = ui {
        page(filled.copy(running = true, phase = "Receiving objects", percent = 62, done = 1_203, total = 1_940))
        snapshot("clone-running-light")
    }
}
