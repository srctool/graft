// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.presentation

import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.presentation.operations.OperationKind
import com.srctool.graft.presentation.operations.OperationsState
import com.srctool.graft.presentation.window.TabContent
import com.srctool.graft.presentation.window.WindowState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppStateTest {
    private var next = 0
    private val windows = WindowState { "id${next++}" }

    private fun repo(path: String): RepositoryLocation {
        val root = Paths.get(path)
        return RepositoryLocation(root, root.resolve(".git"), root.resolve(".git"))
    }

    @Test
    fun `a new window shows Home`() {
        val id = windows.openWindow()
        val window = windows.windows.value.single()
        assertEquals(id, window.id)
        assertEquals(TabContent.Home, window.activeTab.content)
    }

    @Test
    fun `a repository opens once, in whichever window already shows it`() = runTest {
        val first = windows.openWindow()
        val second = windows.openWindow()
        val app = repo("/work/app")
        val tab = windows.openRepository(first, app)
        windows.openHome(first)
        assertEquals(tab, windows.openRepository(second, app))
        assertEquals(tab, windows.windows.value.first { it.id == first }.activeTabId)
        assertEquals(1, windows.windows.value.first { it.id == second }.tabs.size)
        assertEquals(setOf(app), windows.openRepositories.first())
    }

    @Test
    fun `closing a tab brings its neighbour forward, and a last tab leaves Home`() {
        val window = windows.openWindow()
        val home = windows.windows.value.single().tabs.single().id
        val a = windows.openRepository(window, repo("/a"))
        val b = windows.openRepository(window, repo("/b"))
        windows.activate(a)
        windows.closeTab(a)
        assertEquals(b, windows.windows.value.single().activeTabId, "the tab after it")
        windows.closeTab(b)
        assertEquals(home, windows.windows.value.single().activeTabId, "the tab before it, when it was last")
        windows.closeTab(home)
        val left = windows.windows.value.single()
        assertEquals(TabContent.Home, left.activeTab.content)
        assertTrue(left.activeTabId != home, "a fresh Home tab")
    }

    @Test
    fun `the open repositories follow tabs and windows`() = runTest {
        val window = windows.openWindow()
        windows.openRepository(window, repo("/a"))
        val b = repo("/b")
        windows.openRepository(window, b)
        assertEquals(listOf(repo("/a"), b), windows.repositoriesIn(window))
        windows.closeWindow(window)
        assertEquals(emptySet(), windows.openRepositories.first())
    }

    @Test
    fun `tracked work shows while it runs, with its progress, and goes however it ends`() = runTest {
        val operations = OperationsState()
        val gate = CompletableDeferred<Unit>()
        val clone =
            async {
                operations.track(OperationKind.CLONE, Paths.get("/work/new")) { progress ->
                    progress.report(140)
                    gate.await()
                    "done"
                }
            }
        runCurrent()
        val running = operations.running.value.single()
        assertEquals(OperationKind.CLONE, running.kind)
        assertEquals(100, running.percent)
        gate.complete(Unit)
        assertEquals("done", clone.await())
        assertEquals(emptyList(), operations.running.value)

        assertFailsWith<IllegalStateException> { operations.track(OperationKind.SCAN) { error("unreadable") } }
        assertEquals(emptyList(), operations.running.value)
    }
}
