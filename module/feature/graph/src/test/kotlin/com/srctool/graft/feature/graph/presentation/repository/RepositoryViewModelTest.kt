// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.repository

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.srctool.graft.data.app.api.models.PanelLayout
import com.srctool.graft.data.app.api.models.RepositoryUiState
import com.srctool.graft.feature.graph.presentation.graph.FakeUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val SAVED = PanelLayout(open = mapOf("left" to "branches"), saved = true)
private val MOVED = PanelLayout(open = mapOf("right" to "branches"), saved = true)

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val saved = FakeUiState(RepositoryUiState(panels = SAVED))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun TestScope.opened(): RepositoryViewModel {
        val factory = viewModelFactory { initializer { RepositoryViewModel(Paths.get("/work/repo/.git"), saved) } }
        val vm = ViewModelProvider.create(store, factory)[RepositoryViewModel::class]
        vm.onEvent(RepositoryScreenEvent.Opened)
        runCurrent()
        return vm
    }

    @Test
    fun `nothing is restored before the saved choices are read`() = runTest(dispatcher) {
        val factory = viewModelFactory { initializer { RepositoryViewModel(Paths.get("/work/repo/.git"), saved) } }
        val vm = ViewModelProvider.create(store, factory)[RepositoryViewModel::class]
        assertNull(vm.state.value.restore)
        vm.onEvent(RepositoryScreenEvent.LayoutChanged(MOVED))
        advanceTimeBy(RepositoryViewModel.SAVE_PAUSE_MS + 1)
        runCurrent()
        assertEquals(SAVED, saved.value.panels, "a change before the read would overwrite the saved layout")
    }

    @Test
    fun `opening offers the saved layout to restore`() = runTest(dispatcher) {
        val vm = opened()
        assertEquals(SAVED, vm.state.value.restore)
        assertEquals(SAVED, vm.shared.ui.value.panels)
    }

    @Test
    fun `a change is saved once after the pause, and the shared state has it`() = runTest(dispatcher) {
        val vm = opened()
        vm.onEvent(RepositoryScreenEvent.LayoutChanged(PanelLayout(saved = true)))
        runCurrent()
        vm.onEvent(RepositoryScreenEvent.LayoutChanged(MOVED))
        runCurrent()
        advanceTimeBy(RepositoryViewModel.SAVE_PAUSE_MS - 1)
        assertEquals(SAVED, saved.value.panels, "still within the pause")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(MOVED, saved.value.panels)
        assertEquals(MOVED, vm.shared.ui.value.panels)
    }
}
