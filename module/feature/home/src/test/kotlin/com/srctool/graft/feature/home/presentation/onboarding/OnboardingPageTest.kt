// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.presentation.onboarding

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.srctool.graft.core.ui.theme.GraftTheme
import com.srctool.graft.core.ui.theme.GraftThemeMode
import com.srctool.graft.data.git.api.models.GitInstall
import com.srctool.graft.feature.home.presentation.path
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test
import kotlin.test.assertEquals

/** Onboarding per step and state (wireframe S38), with its labels and the commands it shows. */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
class OnboardingPageTest {
    private fun ui(block: ComposeUiTest.() -> Unit) = runDesktopComposeUiTest(700, 560) { block() }

    private fun ComposeUiTest.page(
        state: OnboardingState,
        mode: GraftThemeMode = GraftThemeMode.LIGHT,
        onEvent: (OnboardingEvent) -> Unit = {},
    ) = setContent { GraftTheme(mode) { OnboardingContent(state, onEvent, {}) } }

    private fun ComposeUiTest.snapshot(name: String) = onRoot().captureRoboImage("src/test/snapshots/$name.png")

    private val found = GitCheck.Found(GitInstall("2.54.0", path("bin").resolve("git")))
    private val named =
        OnboardingState(
            step = OnboardingStep.Identity,
            git = found,
            identityLoaded = true,
            name = "Dwiki Riyadi",
            email = "dwiki@example.com",
            configuredName = "Dwiki Riyadi",
            configuredEmail = "dwiki@example.com",
            nameFile = path(".gitconfig"),
            emailFile = path(".gitconfig"),
        )

    @Test
    fun `a step says where it is, and the identity step shows exactly the lines it would run`() = ui {
        page(named.copy(name = "Ann", email = "ann@example.com"))
        onNode(hasContentDescription("Step 2 of 4, Your name and email")).assertExists()
        onNodeWithText("git config --global user.name \"Ann\"").assertExists()
        onNodeWithText("git config --global user.email \"ann@example.com\"").assertExists()
        onNodeWithText("Set and continue").assertExists()
    }

    @Test
    fun `unchanged identity says nothing to write and offers Next`() = ui {
        page(named)
        onNodeWithText("Nothing to write.").assertExists()
        onNodeWithText("Next").assertExists()
    }

    @Test
    fun `too old offers the fix, a way to skip, and a new check`() = ui {
        val events = mutableStateListOf<OnboardingEvent>()
        page(OnboardingState(git = GitCheck.TooOld("2.30.0"))) { events += it }
        onNodeWithText("Git 2.30.0 is too old. Graft needs 2.38 or newer.").assertExists()
        onNodeWithText("Skip this step").assertExists()
        onNodeWithText("Check again").performClick()
        assertEquals(listOf<OnboardingEvent>(OnboardingEvent.GitRechecked), events.toList())
    }

    @Test
    fun `the install command is the one for the system`() {
        assertEquals(listOf("macOS" to "xcode-select --install"), installHints("Mac OS X"))
        assertEquals(listOf("Windows" to "winget install --id Git.Git -e"), installHints("Windows 11"))
        assertEquals(3, installHints("Linux").size)
    }

    @Test
    fun `screenshot, step 1, found`() = ui {
        page(OnboardingState(git = found))
        snapshot("onboarding-git-light")
    }

    @Test
    fun `screenshot, step 1, too old, dark`() = ui {
        page(OnboardingState(git = GitCheck.TooOld("2.30.0")), GraftThemeMode.DARK)
        snapshot("onboarding-git-old-dark")
    }

    @Test
    fun `screenshot, step 2, identity changed`() = ui {
        page(named.copy(name = "Ann Lee", email = "ann@example.com"))
        snapshot("onboarding-identity-light")
    }

    @Test
    fun `screenshot, step 3, look`() = ui {
        page(named.copy(step = OnboardingStep.Look))
        snapshot("onboarding-look-light")
    }

    @Test
    fun `screenshot, step 4, repositories, dark`() = ui {
        page(named.copy(step = OnboardingStep.Repositories), GraftThemeMode.DARK)
        snapshot("onboarding-repositories-dark")
    }
}
