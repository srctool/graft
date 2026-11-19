// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.presentation.onboarding

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.srctool.graft.data.app.api.models.AppSettings
import com.srctool.graft.data.app.api.models.Density
import com.srctool.graft.data.app.api.models.DiffStyle
import com.srctool.graft.data.app.api.models.ThemeChoice
import com.srctool.graft.data.git.api.models.GitInstall
import com.srctool.graft.data.git.api.models.GitInstallStatus
import com.srctool.graft.feature.home.presentation.FakeFailure
import com.srctool.graft.feature.home.presentation.FakeIdentity
import com.srctool.graft.feature.home.presentation.FakeInstall
import com.srctool.graft.feature.home.presentation.FakeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val install = FakeInstall()
    private val identity = FakeIdentity()
    private val settings = FakeSettings(AppSettings(onboarded = false))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun TestScope.opened(): OnboardingViewModel {
        val vm =
            ViewModelProvider.create(
                store,
                viewModelFactory { initializer { OnboardingViewModel(install, identity, settings) } },
            )[OnboardingViewModel::class]
        vm.onEvent(OnboardingEvent.Opened)
        advanceUntilIdle()
        return vm
    }

    private fun TestScope.send(vm: OnboardingViewModel, vararg events: OnboardingEvent) {
        events.forEach(vm::onEvent)
        advanceUntilIdle()
    }

    @Test
    fun `step 1 reports git as found, too old or missing, and checks again on request`() = runTest(dispatcher) {
        val vm = opened()
        assertEquals(GitCheck.Found(GitInstall("2.54.0", null)), vm.state.value.git)
        install.status = GitInstallStatus.TooOld("2.30.0", "2.38.0")
        send(vm, OnboardingEvent.GitRechecked)
        assertEquals(GitCheck.TooOld("2.30.0"), vm.state.value.git)
        install.status = GitInstallStatus.Missing
        send(vm, OnboardingEvent.GitRechecked)
        assertEquals(GitCheck.Missing, vm.state.value.git)
        assertEquals(3, install.checks)
        install.status = GitInstallStatus.Unreadable(FakeFailure())
        send(vm, OnboardingEvent.GitRechecked)
        assertIs<GitCheck.Failed>(vm.state.value.git)
    }

    @Test
    fun `every step can be skipped with Next, and Back keeps what was typed`() = runTest(dispatcher) {
        val vm = opened()
        send(vm, OnboardingEvent.Next)
        assertEquals(OnboardingStep.Identity, vm.state.value.step)
        send(vm, OnboardingEvent.NameChanged("Ann"), OnboardingEvent.Next, OnboardingEvent.Back)
        assertEquals("Ann", vm.state.value.name)
        send(vm, OnboardingEvent.Next, OnboardingEvent.Next, OnboardingEvent.Next)
        assertEquals(OnboardingStep.Repositories, vm.state.value.step)
        assertTrue(vm.state.value.last)
        // Nothing was written to git's config on the way.
        assertTrue(identity.writes.isEmpty())
    }

    @Test
    fun `identity starts from what git has, and shows only the lines that would change`() = runTest(dispatcher) {
        val vm = opened()
        assertEquals("Dwiki Riyadi", vm.state.value.name)
        assertEquals(emptyList(), vm.state.value.commands)
        send(vm, OnboardingEvent.EmailChanged("new@example.invalid"))
        assertEquals(listOf("git config --global user.email \"new@example.invalid\""), vm.state.value.commands)
        send(vm, OnboardingEvent.NameChanged("A \"quoted\" name"))
        assertEquals(
            listOf(
                "git config --global user.name \"A \\\"quoted\\\" name\"",
                "git config --global user.email \"new@example.invalid\"",
            ),
            vm.state.value.commands,
        )
    }

    @Test
    fun `set and continue writes the changed identity and moves on, and unchanged writes nothing`() =
        runTest(dispatcher) {
            val vm = opened()
            send(vm, OnboardingEvent.Next, OnboardingEvent.IdentityApplied)
            assertTrue(identity.writes.isEmpty())
            assertEquals(OnboardingStep.Look, vm.state.value.step)
            send(vm, OnboardingEvent.Back, OnboardingEvent.NameChanged("Ann"), OnboardingEvent.IdentityApplied)
            assertEquals(listOf("Ann" to "dwiki@example.invalid"), identity.writes)
            assertEquals(OnboardingStep.Look, vm.state.value.step)
            assertEquals("Ann", vm.state.value.configuredName)
        }

    @Test
    fun `a refused identity stays on the step with the reason, and editing clears it`() = runTest(dispatcher) {
        identity.failure = FakeFailure()
        val vm = opened()
        send(vm, OnboardingEvent.Next, OnboardingEvent.NameChanged("Ann"), OnboardingEvent.IdentityApplied)
        assertEquals(OnboardingStep.Identity, vm.state.value.step)
        assertIs<FakeFailure>(vm.state.value.error)
        assertEquals(false, vm.state.value.running)
        send(vm, OnboardingEvent.NameChanged("Anne"))
        assertEquals(null, vm.state.value.error)
    }

    @Test
    fun `each look choice is saved at once`() = runTest(dispatcher) {
        val vm = opened()
        send(
            vm,
            OnboardingEvent.ThemeChosen(ThemeChoice.DARK),
            OnboardingEvent.DensityChosen(Density.COMFORTABLE),
            OnboardingEvent.DiffStyleChosen(DiffStyle.SIDE_BY_SIDE),
        )
        assertEquals(ThemeChoice.DARK, settings.value.theme)
        assertEquals(Density.COMFORTABLE, settings.value.density)
        assertEquals(DiffStyle.SIDE_BY_SIDE, settings.value.diffStyle)
        assertEquals(settings.value, vm.state.value.settings)
    }

    @Test
    fun `finishing records the first run and closes, and so does closing the dialog`() = runTest(dispatcher) {
        val vm = opened()
        val effects = mutableListOf<OnboardingEffect>()
        val collector = launch { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        send(vm, OnboardingEvent.Finished)
        assertTrue(settings.value.onboarded)
        assertEquals(listOf<OnboardingEffect>(OnboardingEffect.Done), effects)
        collector.cancel()
    }
}
