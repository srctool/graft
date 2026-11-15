// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.configuration

import kotlin.test.Test
import kotlin.test.assertEquals

/** Platform Integration 3.6: saved window bounds are clamped to the screens that exist now. */
class WindowPlacementTest {
    private val laptop = WindowBounds(0, 0, 1512, 945)
    private val monitor = WindowBounds(1512, -200, 2560, 1440)

    @Test
    fun `a window fully on a screen stays where it was`() {
        val saved = WindowBounds(100, 80, 1280, 800)
        assertEquals(saved, WindowPlacement.clamp(saved, listOf(laptop, monitor)))
    }

    @Test
    fun `a window on a disconnected monitor moves to the first screen, centered`() {
        val saved = WindowBounds(2000, 100, 1280, 800)
        assertEquals(WindowBounds(116, 72, 1280, 800), WindowPlacement.clamp(saved, listOf(laptop)))
    }

    @Test
    fun `a window larger than its screen shrinks to fit`() {
        val saved = WindowBounds(0, 0, 3000, 2000)
        assertEquals(WindowBounds(0, 0, 1512, 945), WindowPlacement.clamp(saved, listOf(laptop)))
    }

    @Test
    fun `a window partly off its screen is pulled back in`() {
        val saved = WindowBounds(1000, 500, 1280, 800)
        assertEquals(WindowBounds(232, 145, 1280, 800), WindowPlacement.clamp(saved, listOf(laptop)))
    }

    @Test
    fun `a window mostly on the second monitor stays on it`() {
        val saved = WindowBounds(1400, 0, 1280, 800)
        assertEquals(WindowBounds(1512, 0, 1280, 800), WindowPlacement.clamp(saved, listOf(laptop, monitor)))
    }

    @Test
    fun `no screens keeps the saved bounds`() {
        val saved = WindowBounds(10, 10, 800, 600)
        assertEquals(saved, WindowPlacement.clamp(saved, emptyList()))
    }
}
