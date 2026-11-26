// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.repository

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.srctool.graft.core.ui.components.DockSide
import com.srctool.graft.data.app.api.models.PanelLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The keeper between a dock and its owner: it restores what it is given and reports what the user changes. */
@OptIn(ExperimentalTestApi::class)
class PanelLayoutKeeperUiTest {
    private val changes = ArrayList<PanelLayout>()

    @Test
    fun `a saved layout is restored once it arrives, and only later changes are reported`() {
        val dock = defaultDock()
        val restore = mutableStateOf<PanelLayout?>(null)
        runDesktopComposeUiTest {
            setContent { PanelLayoutKeeper(dock, restore.value) { changes += it } }
            waitForIdle()
            restore.value = PanelLayout(open = mapOf("bottom" to "gitlog"), saved = true)
            waitForIdle()
            assertEquals(mapOf(DockSide.BOTTOM to "gitlog"), dock.snapshot().open)
            assertTrue(changes.isEmpty(), "restoring is not a change")
            dock.show("branches", DockSide.LEFT)
            waitForIdle()
            assertEquals("branches", changes.last().open["left"])
        }
    }

    @Test
    fun `a repository with no saved layout keeps what the dock started with`() {
        val dock = defaultDock()
        val before = dock.snapshot()
        runDesktopComposeUiTest {
            setContent { PanelLayoutKeeper(dock, PanelLayout()) { changes += it } }
            waitForIdle()
            assertEquals(before, dock.snapshot())
            assertTrue(changes.isEmpty())
        }
    }
}
