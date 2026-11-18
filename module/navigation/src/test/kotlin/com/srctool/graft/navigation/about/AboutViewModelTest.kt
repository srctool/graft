// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.about

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.srctool.graft.data.git.api.models.GitInstall
import com.srctool.graft.data.git.api.models.GitInstallStatus
import com.srctool.graft.data.git.api.repositories.GitInstallRepository
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeInstall(var status: GitInstallStatus) : GitInstallRepository {
    override suspend fun check(): GitInstallStatus = status
}

@OptIn(ExperimentalCoroutinesApi::class)
class AboutViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val install = FakeInstall(GitInstallStatus.Ready(GitInstall("2.54.0", null)))
    private val entries =
        listOf(
            LicenseEntry("okio", "3.9.0", "Apache License, Version 2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
            LicenseEntry("ktor", "3.0.0", "Apache License, Version 2.0", null),
            LicenseEntry("junit", "5.11", "Eclipse Public License v2.0", null),
        )

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun TestScope.opened(): AboutViewModel {
        val vm =
            ViewModelProvider.create(
                store,
                viewModelFactory {
                    initializer {
                        AboutViewModel(
                            install,
                            { entries },
                            { "0.1.0" },
                            SystemInfo("21.0.12", "macOS 27.0 (aarch64)"),
                            dispatcher,
                        )
                    }
                },
            )[AboutViewModel::class]
        vm.onEvent(AboutEvent.Opened)
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `opening gathers the version, git, the system and the license list`() = runTest(dispatcher) {
        val state = opened().state.value
        assertEquals("0.1.0", state.version)
        assertEquals("2.54.0", state.git)
        assertEquals(entries, state.licenses)
        assertTrue(state.loaded)
    }

    @Test
    fun `without a usable git the version line says so`() = runTest(dispatcher) {
        install.status = GitInstallStatus.Missing
        val state = opened().state.value
        assertNull(state.git)
        assertEquals("Graft 0.1.0\nGit not found\nJDK 21.0.12\nOS macOS 27.0 (aarch64)", state.versionInfo("not found"))
    }

    @Test
    fun `the filter matches name or license, and moving stays inside what's shown`() = runTest(dispatcher) {
        val vm = opened()
        vm.onEvent(AboutEvent.QueryTyped("apache"))
        advanceUntilIdle()
        assertEquals(listOf("okio", "ktor"), vm.state.value.shown.map { it.name })
        vm.onEvent(AboutEvent.Moved(5))
        advanceUntilIdle()
        assertEquals("ktor", vm.state.value.current?.name)
        vm.onEvent(AboutEvent.QueryTyped("JUNIT"))
        advanceUntilIdle()
        assertEquals(0, vm.state.value.selected)
        assertEquals("junit", vm.state.value.current?.name)
        vm.onEvent(AboutEvent.QueryTyped("nothing"))
        advanceUntilIdle()
        assertNull(vm.state.value.current)
    }

    @Test
    fun `copying version info sends exactly the bug-report text`() = runTest(dispatcher) {
        val vm = opened()
        val effects = mutableListOf<AboutEffect>()
        val collector = launch { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        vm.onEvent(AboutEvent.CopyVersionInfo)
        advanceUntilIdle()
        assertEquals(
            listOf<AboutEffect>(
                AboutEffect.Copy("Graft 0.1.0\nGit 2.54.0\nJDK 21.0.12\nOS macOS 27.0 (aarch64)"),
            ),
            effects,
        )
        collector.cancel()
    }
}
