// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.shell

import com.srctool.graft.data.app.api.models.SavedWindow
import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.presentation.window.TabContent
import com.srctool.graft.presentation.window.WindowState
import java.nio.file.Path
import java.nio.file.Paths

/** The folders of the repository tabs [windowId] has open, in tab order, and which of them is in front. */
public data class WindowTabs(val folders: List<String>, val active: Int)

/**
 * [windowId]'s repository tabs as they're saved: folders in tab order, and the index in front among them (-1 for Home).
 */
public fun WindowState.tabsOf(windowId: String): WindowTabs {
    val window = windows.value.firstOrNull { it.id == windowId } ?: return WindowTabs(emptyList(), -1)
    val repositories = window.tabs.filter { it.content is TabContent.Repository }
    val folders = repositories.map { (it.content as TabContent.Repository).root.toString() }
    return WindowTabs(folders, repositories.indexOfFirst { it.id == window.activeTabId })
}

/** What [SavedWindow] keeps of this window's tabs, with the bounds the app read from its window. */
public fun WindowState.toSaved(windowId: String, x: Int, y: Int, width: Int, height: Int, maximized: Boolean) =
    tabsOf(windowId).let { SavedWindow(x, y, width, height, maximized, it.folders, it.active) }

/**
 * Opens [saved]'s repository tabs in [windowId], in order, and brings the saved one to the front. [locate] turns a
 * folder into its repository; a folder that no longer holds one is skipped and returned, so the window can say so.
 */
public suspend fun WindowState.restoreTabs(
    windowId: String,
    saved: SavedWindow,
    locate: suspend (Path) -> RepositoryLocation?,
): List<String> {
    val missing = mutableListOf<String>()
    val opened = mutableListOf<String?>()
    saved.tabs.forEach { folder ->
        val location = locate(Paths.get(folder))
        if (location == null) {
            missing += folder
        } else {
            opened += openRepository(windowId, location)
        }
    }
    val front = saved.activeTab
    // The saved index counts every saved tab; a skipped folder shifts the ones after it.
    val skippedBefore = saved.tabs.take(front.coerceAtLeast(0)).count { it in missing }
    opened.getOrNull(front - skippedBefore)?.takeIf { front >= 0 && saved.tabs[front] !in missing }?.let(::activate)
        ?: windows.value.firstOrNull { it.id == windowId }?.tabs?.firstOrNull { it.content == TabContent.Home }
            ?.let { activate(it.id) }
    return missing
}
