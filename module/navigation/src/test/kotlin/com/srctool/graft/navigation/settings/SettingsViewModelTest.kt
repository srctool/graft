// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.settings

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.srctool.graft.data.app.api.models.AppSettings
import com.srctool.graft.data.app.api.models.Density
import com.srctool.graft.data.app.api.models.DiffStyle
import com.srctool.graft.data.app.api.models.Saved
import com.srctool.graft.data.app.api.models.SavedStatus
import com.srctool.graft.data.app.api.models.ThemeChoice
import com.srctool.graft.data.app.api.repositories.SettingsRepository
import com.srctool.sinew.models.ErrorMessage
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.SinewException
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class Refused : SinewException("DATA", ExceptionLayer.Repository, "TEST") {
    override val typeCode: String = "WRITE"
    override val errorMessage: ErrorMessage = ErrorMessage.Local("test.refused", emptyMap(), "Not saved.")
}

internal class FakeSettings(
    var value: AppSettings = AppSettings(),
    var status: SavedStatus = SavedStatus.Current,
    var failure: SinewException? = null,
) : SettingsRepository {
    var writes = 0

    override suspend fun read(): Result<Saved<AppSettings>> = Result.Success(Saved(value, status))

    override suspend fun update(change: (AppSettings) -> AppSettings): Result<AppSettings> {
        failure?.let { return Result.Failure(it) }
        writes++
        value = change(value)
        return Result.Success(value)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val settings = FakeSettings()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun TestScope.opened(): SettingsViewModel {
        val vm =
            ViewModelProvider.create(store, viewModelFactory { initializer { SettingsViewModel(settings) } })[
                SettingsViewModel::class,
            ]
        vm.onEvent(SettingsEvent.Opened)
        advanceUntilIdle()
        return vm
    }

    private fun TestScope.send(vm: SettingsViewModel, vararg events: SettingsEvent) {
        events.forEach(vm::onEvent)
        advanceUntilIdle()
    }

    @Test
    fun `opening reads the saved settings, and the inline limit shows in MB`() = runTest(dispatcher) {
        settings.value = AppSettings(theme = ThemeChoice.DARK, inlineDiffLimitBytes = 5L * 1024 * 1024)
        val vm = opened()
        assertTrue(vm.state.value.loaded)
        assertEquals(ThemeChoice.DARK, vm.state.value.settings.theme)
        assertEquals("5", vm.state.value.limitText)
    }

    @Test
    fun `each change is written at once, and nothing else changes`() = runTest(dispatcher) {
        val vm = opened()
        send(
            vm,
            SettingsEvent.ThemeChosen(ThemeChoice.LIGHT),
            SettingsEvent.DensityChosen(Density.COMFORTABLE),
            SettingsEvent.DiffStyleChosen(DiffStyle.SIDE_BY_SIDE),
            SettingsEvent.IgnoreWhitespaceToggled,
        )
        assertEquals(
            AppSettings(
                theme = ThemeChoice.LIGHT,
                density = Density.COMFORTABLE,
                diffStyle = DiffStyle.SIDE_BY_SIDE,
                ignoreWhitespace = true,
            ),
            settings.value,
        )
        assertEquals(4, settings.writes)
        assertEquals(settings.value, vm.state.value.settings)
    }

    @Test
    fun `the inline limit is saved in bytes once it's a whole number of MB, and a bad one isn't saved`() =
        runTest(dispatcher) {
            val vm = opened()
            send(vm, SettingsEvent.LimitTyped("8"))
            assertEquals(8L * 1024 * 1024, settings.value.inlineDiffLimitBytes)
            send(vm, SettingsEvent.LimitTyped("abc"))
            assertTrue(vm.state.value.limitInvalid)
            send(vm, SettingsEvent.LimitTyped("0"))
            assertTrue(vm.state.value.limitInvalid)
            assertEquals(8L * 1024 * 1024, settings.value.inlineDiffLimitBytes)
            assertEquals(1, settings.writes)
        }

    @Test
    fun `a file from a newer Graft is shown and never written`() = runTest(dispatcher) {
        settings.status = SavedStatus.Newer(9)
        val vm = opened()
        assertTrue(vm.state.value.readOnly)
        send(vm, SettingsEvent.ThemeChosen(ThemeChoice.DARK), SettingsEvent.LimitTyped("4"))
        assertEquals(0, settings.writes)
    }

    @Test
    fun `a refused write keeps the old value and says why`() = runTest(dispatcher) {
        val vm = opened()
        settings.failure = Refused()
        send(vm, SettingsEvent.ThemeChosen(ThemeChoice.DARK))
        assertEquals(ThemeChoice.SYSTEM, vm.state.value.settings.theme)
        assertNotNull(vm.state.value.error)
        settings.failure = null
        send(vm, SettingsEvent.ThemeChosen(ThemeChoice.DARK))
        assertNull(vm.state.value.error)
    }

    @Test
    fun `search and page choice are local, and the welcome button asks the host`() = runTest(dispatcher) {
        val vm = opened()
        val effects = mutableListOf<SettingsEffect>()
        val collector = launch { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        send(vm, SettingsEvent.QueryTyped("diff"), SettingsEvent.PageChosen(SettingsPage.Diffs))
        assertEquals("diff", vm.state.value.query)
        assertEquals(SettingsPage.Diffs, vm.state.value.page)
        send(vm, SettingsEvent.WelcomeAgain)
        assertEquals(listOf<SettingsEffect>(SettingsEffect.WelcomeAgain), effects)
        assertEquals(0, settings.writes)
        collector.cancel()
    }
}
