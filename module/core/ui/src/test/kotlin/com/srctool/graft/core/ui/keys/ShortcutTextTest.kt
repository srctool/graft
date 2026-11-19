// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.keys

import kotlin.test.Test
import kotlin.test.assertEquals

class ShortcutTextTest {
    @Test
    fun `macOS shows symbols in its own order, with no separators`() {
        assertEquals("⌘,", shortcutText("Mod+,", mac = true))
        assertEquals("⇧⌘O", shortcutText("Mod+Shift+O", mac = true))
        assertEquals("⌥⇧⌘A", shortcutText("Mod+Shift+Alt+A", mac = true))
        assertEquals("F12", shortcutText("F12", mac = true))
    }

    @Test
    fun `Windows and Linux show words, with Mod as Ctrl`() {
        assertEquals("Ctrl+,", shortcutText("Mod+,", mac = false))
        assertEquals("Ctrl+Alt+Shift+O", shortcutText("Mod+Shift+Alt+O", mac = false))
    }
}
