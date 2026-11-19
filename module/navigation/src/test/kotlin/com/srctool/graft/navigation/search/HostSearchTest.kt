// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.search

import com.srctool.graft.presentation.search.SearchScope
import com.srctool.graft.presentation.search.SearchTarget
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostSearchTest {
    @Test
    fun `two Shift presses within the window open it, and anything else between them doesn't`() {
        val d = DoubleShift(window = 400)
        assertFalse(d.onKeyDown(isShift = true, otherModifiers = false, now = 1_000))
        assertTrue(d.onKeyDown(isShift = true, otherModifiers = false, now = 1_300))
        // A third press starts over.
        assertFalse(d.onKeyDown(isShift = true, otherModifiers = false, now = 1_400))
        // Too slow.
        assertFalse(d.onKeyDown(isShift = true, otherModifiers = false, now = 2_000))
        // A letter between the two Shifts forgets the first.
        assertFalse(d.onKeyDown(isShift = false, otherModifiers = false, now = 2_100))
        assertFalse(d.onKeyDown(isShift = true, otherModifiers = false, now = 2_200))
        // Shift held with Ctrl or Alt is a shortcut, not a Shift press.
        assertFalse(d.onKeyDown(isShift = true, otherModifiers = false, now = 5_000))
        assertFalse(d.onKeyDown(isShift = true, otherModifiers = true, now = 5_100))
        assertFalse(d.onKeyDown(isShift = true, otherModifiers = false, now = 5_200))
    }

    @Test
    fun `the window's own actions show those with a shortcut before typing, all after`() = runBlocking {
        val source = HostActionSearchSource()
        assertEquals(listOf("Settings…", "Home"), source.candidates("", SearchScope()).map { it.title })
        val all = source.candidates("a", SearchScope())
        assertEquals(
            listOf(HostActions.SETTINGS, HostActions.ABOUT, HostActions.WELCOME, HostActions.HOME)
                .map { SearchTarget.RunAction(it) },
            all.map { it.target },
        )
    }
}
