// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.configuration

/** A window or screen rectangle in the desktop's coordinates (pixels; secondary screens may be negative). */
public data class WindowBounds(val x: Int, val y: Int, val width: Int, val height: Int) {
    internal val right: Int get() = x + width
    internal val bottom: Int get() = y + height

    internal fun overlap(other: WindowBounds): Long {
        val w = minOf(right, other.right) - maxOf(x, other.x)
        val h = minOf(bottom, other.bottom) - maxOf(y, other.y)
        return if (w > 0 && h > 0) w.toLong() * h else 0
    }
}

/** Where a saved window may open now that screens may have changed (Platform Integration 3.6). */
public object WindowPlacement {
    /**
     * [saved] moved onto the screen it overlaps most, shrunk to fit it and pulled fully inside. A window on no current
     * screen (a monitor was unplugged) is centered on the first of [screens]. With no screens, [saved] is returned.
     */
    public fun clamp(saved: WindowBounds, screens: List<WindowBounds>): WindowBounds {
        if (screens.isEmpty()) return saved
        val best = screens.maxBy { saved.overlap(it) }
        val onScreen = saved.overlap(best) > 0
        val screen = if (onScreen) best else screens.first()
        val width = minOf(saved.width, screen.width)
        val height = minOf(saved.height, screen.height)
        if (!onScreen) {
            return WindowBounds(
                screen.x + (screen.width - width) / 2,
                screen.y + (screen.height - height) / 2,
                width,
                height,
            )
        }
        return WindowBounds(
            saved.x.coerceIn(screen.x, screen.right - width),
            saved.y.coerceIn(screen.y, screen.bottom - height),
            width,
            height,
        )
    }
}
