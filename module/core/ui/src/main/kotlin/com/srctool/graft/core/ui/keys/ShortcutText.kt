// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.keys

/**
 * A shortcut as the Keyboard Shortcuts note writes it (`Mod+Shift+O`) in the form this OS shows: symbols without
 * separators on macOS (`⇧⌘O`), words joined by `+` elsewhere (`Ctrl+Shift+O`). **Mod** is `Cmd` on macOS and `Ctrl`
 * elsewhere.
 */
public fun shortcutText(
    spec: String,
    mac: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac"),
): String {
    val parts = spec.split('+')
    val key = parts.last()
    val modifiers = parts.dropLast(1).toSet()
    return if (mac) {
        // macOS orders them control, option, shift, command.
        listOf("Ctrl" to "⌃", "Alt" to "⌥", "Shift" to "⇧", "Mod" to "⌘")
            .filter { it.first in modifiers }
            .joinToString("") { it.second } + key
    } else {
        (listOf("Mod", "Ctrl", "Alt", "Shift").filter { it in modifiers }.map { if (it == "Mod") "Ctrl" else it } + key)
            .joinToString("+")
    }
}
