// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.repository

import com.srctool.graft.core.ui.components.DockLayoutSnapshot
import com.srctool.graft.core.ui.components.DockSide
import com.srctool.graft.core.ui.components.ToolWindow
import com.srctool.graft.core.ui.theme.GraftIcon
import com.srctool.graft.data.app.api.models.PanelLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PanelLayoutKeeperTest {
    @Test
    fun `a dock layout is saved under dock names and read back as it was`() {
        val snapshot =
            DockLayoutSnapshot(
                sides = mapOf("operations" to DockSide.RIGHT),
                open = mapOf(DockSide.LEFT to "branches", DockSide.BOTTOM to "gitlog"),
                sizes = mapOf(DockSide.LEFT to 300f, DockSide.BOTTOM to 180f),
            )
        val layout = snapshot.toLayout()
        assertEquals(mapOf("left" to "branches", "bottom" to "gitlog"), layout.open)
        assertTrue(layout.saved)
        assertEquals(snapshot, layout.toSnapshot())
    }

    @Test
    fun `a dock name this Graft doesn't know is ignored`() {
        val layout = PanelLayout(open = mapOf("top" to "x", "left" to "branches"), sizes = mapOf("floating" to 9f))
        val snapshot = layout.toSnapshot()
        assertEquals(mapOf(DockSide.LEFT to "branches"), snapshot.open)
        assertTrue(snapshot.sizes.isEmpty())
    }

    private fun window(id: String) = ToolWindow(id, id, GraftIcon.LIST, DockSide.LEFT) {}

    @Test
    fun `a contributed window is added after the screen's own and can't take an id that is used`() {
        val own = listOf(window("branches"), window("details"))
        val all = withContributions(own, listOf(window("changes"), window("details"), window("changes")))
        assertEquals(listOf("branches", "details", "changes"), all.map { it.id })
        assertTrue(all[1] === own[1], "the built-in keeps its id")
    }

    @Test
    fun `a contributed window's place is saved and read back by its id`() {
        val snapshot =
            DockLayoutSnapshot(
                sides = mapOf("changes" to DockSide.BOTTOM),
                open = mapOf(DockSide.BOTTOM to "changes"),
                sizes = emptyMap(),
            )
        assertEquals(snapshot, snapshot.toLayout().toSnapshot())
    }
}
