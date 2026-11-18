// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.presentation.initrepo

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.srctool.graft.core.ui.theme.GraftTheme
import com.srctool.graft.core.ui.theme.GraftThemeMode
import com.srctool.graft.feature.home.domain.api.models.LicenseInfo
import com.srctool.graft.feature.home.presentation.clone.FolderStatus
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test

/** Init per state (wireframe S3). */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
class InitPageTest {
    private fun ui(block: ComposeUiTest.() -> Unit) = runDesktopComposeUiTest(700, 760) { block() }

    private fun ComposeUiTest.page(state: InitState, mode: GraftThemeMode = GraftThemeMode.LIGHT) =
        setContent { GraftTheme(mode) { InitDialogContent(state, {}, {}) } }

    private fun ComposeUiTest.snapshot(name: String) = onRoot().captureRoboImage("src/test/snapshots/$name.png")

    private val base =
        InitState(
            folder = "/work/notes",
            folderStatus = FolderStatus.Free,
            gitignores = listOf("Kotlin", "Node", "Python"),
            licenses =
            listOf(
                LicenseInfo("MIT", "MIT License", needsHolder = true),
                LicenseInfo("MPL-2.0", "Mozilla Public License 2.0", needsHolder = false),
            ),
            identityName = "Dwiki Riyadi",
            identityEmail = "dwiki@example.com",
        )

    @Test
    fun `screenshot, init, light`() = ui {
        val filled =
            base.copy(gitignore = "Kotlin", license = "MIT", holder = "Dwiki Riyadi", readme = true, firstCommit = true)
        page(filled)
        snapshot("init-light")
    }

    @Test
    fun `screenshot, init, a folder that is already a repository, dark`() = ui {
        page(base.copy(isRepository = true), GraftThemeMode.DARK)
        snapshot("init-repository-dark")
    }
}
