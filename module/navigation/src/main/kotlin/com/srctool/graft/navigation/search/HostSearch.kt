// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.search

import com.srctool.graft.core.ui.keys.shortcutText
import com.srctool.graft.core.ui.l10n.GraftLocalizer
import com.srctool.graft.presentation.search.SearchCandidate
import com.srctool.graft.presentation.search.SearchGroup
import com.srctool.graft.presentation.search.SearchKind
import com.srctool.graft.presentation.search.SearchScope
import com.srctool.graft.presentation.search.SearchSource
import com.srctool.graft.presentation.search.SearchTarget

/** The ids of the actions the host itself runs. */
internal object HostActions {
    const val SETTINGS = "host.settings"
    const val ABOUT = "host.about"
    const val WELCOME = "host.welcome"
    const val HOME = "host.home"
}

/** The window's own commands: Settings, About, Welcome and Home. */
internal class HostActionSearchSource(private val l10n: GraftLocalizer = GraftLocalizer()) : SearchSource {
    override val group: SearchGroup = SearchGroup.Actions

    override suspend fun candidates(query: String, scope: SearchScope): List<SearchCandidate> {
        val all =
            listOf(
                action(HostActions.SETTINGS, "host.action.settings", "Settings…", "Mod+,"),
                action(HostActions.ABOUT, "host.action.about", "About Graft", null),
                action(HostActions.WELCOME, "host.action.welcome", "Welcome…", null),
                action(HostActions.HOME, "host.action.home", "Home", "Mod+T"),
            )
        return if (query.isBlank()) all.filter { it.shortcut != null } else all
    }

    private fun action(id: String, key: String, fallback: String, shortcut: String?) = SearchCandidate(
        key = "action:$id",
        kind = SearchKind.Action,
        title = l10n.resolve(key, emptyMap()) ?: fallback,
        target = SearchTarget.RunAction(id),
        shortcut = shortcut?.let(::shortcutText),
    )
}

/**
 * Double Shift: two presses of Shift within [window] ms with no other key between them. Feed it every key-down with
 * whether the key is Shift; it says when the second Shift lands.
 */
internal class DoubleShift(private val window: Long = 400) {
    private var lastShift = Long.MIN_VALUE

    /** True on the second Shift press. Any other key forgets the first. */
    fun onKeyDown(isShift: Boolean, otherModifiers: Boolean, now: Long): Boolean {
        if (!isShift || otherModifiers) {
            lastShift = Long.MIN_VALUE
            return false
        }
        val second = lastShift != Long.MIN_VALUE && now - lastShift <= window
        lastShift = if (second) Long.MIN_VALUE else now
        return second
    }
}
