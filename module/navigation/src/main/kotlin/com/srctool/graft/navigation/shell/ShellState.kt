// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.shell

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.srctool.graft.core.ui.components.DockLayoutState
import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.presentation.window.AppWindow
import com.srctool.graft.presentation.window.Tab
import com.srctool.graft.presentation.window.TabContent
import com.srctool.graft.presentation.window.WindowState
import java.nio.file.Path

/** What a tab's back stack holds (Navigation 3 keys carry only what names the screen, never state). */
internal sealed interface TabKey {
    data object Home : TabKey

    /** A repository's screen, by the folder git runs in. */
    data class Repository(val root: String) : TabKey
}

/**
 * One window's navigation: its tabs (from [windows]), each tab's own back stack, and each repository tab's panel
 * layout. A tab's stack and layout live as long as the tab, so switching away and back finds both as left (Task 20
 * wireframe). [locate] turns a folder into the repository it holds, or null when it holds none.
 */
internal class ShellState(
    private val windows: WindowState,
    val windowId: String,
    private val locate: suspend (Path) -> RepositoryLocation?,
    private val newDock: () -> DockLayoutState = { DockLayoutState() },
) {
    private val stacks = mutableStateMapOf<String, SnapshotStateList<TabKey>>()
    private val docks = mutableMapOf<String, DockLayoutState>()

    val window: AppWindow? get() = windows.windows.value.firstOrNull { it.id == windowId }

    val activeTab: Tab? get() = window?.activeTab

    /** [tab]'s back stack, newest last; it starts with the tab's own screen. */
    fun stack(tab: Tab): SnapshotStateList<TabKey> = stacks.getOrPut(tab.id) {
        mutableStateListOf(
            when (val content = tab.content) {
                TabContent.Home -> TabKey.Home
                is TabContent.Repository -> TabKey.Repository(content.root.toString())
            },
        )
    }

    /** [tab]'s panel layout (the repository screen applies and saves the saved one). */
    fun dock(tab: Tab): DockLayoutState = docks.getOrPut(tab.id, newDock)

    private fun stackOf(tabId: String): SnapshotStateList<TabKey>? =
        window?.tabs?.firstOrNull { it.id == tabId }?.let(::stack)

    fun push(tabId: String, key: TabKey) {
        val stack = stackOf(tabId) ?: return
        if (stack.last() != key) stack.add(key)
    }

    /** Pops [tabId]'s stack; false when only the tab's own screen is left. */
    fun back(tabId: String): Boolean {
        val stack = stackOf(tabId) ?: return false
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Drops the stacks and layouts of tabs that no longer exist; call when the window's tabs change. */
    fun prune() {
        val live = window?.tabs?.map { it.id }?.toSet().orEmpty()
        stacks.keys.filter { it !in live }.forEach { stacks.remove(it) }
        docks.keys.filter { it !in live }.forEach { docks.remove(it) }
    }

    /**
     * Brings the repository at [folder] to the front, opening a tab for it unless one shows it already. False when
     * the folder holds no repository; nothing changes then.
     */
    suspend fun open(folder: Path): Boolean {
        val location = locate(folder) ?: return false
        windows.openRepository(windowId, location)
        return true
    }

    fun activate(tabId: String) = windows.activate(tabId)

    fun goHome() {
        window?.tabs?.firstOrNull { it.content == TabContent.Home }?.let { windows.activate(it.id) }
    }

    fun close(tabId: String) {
        // Home is always there; it is not closed, only left.
        if (window?.tabs?.firstOrNull { it.id == tabId }?.content == TabContent.Home) return
        windows.closeTab(tabId)
        prune()
    }

    /** The tab [by] places after the active one, wrapping around. */
    fun step(by: Int) {
        val tabs = window?.tabs ?: return
        val at = tabs.indexOfFirst { it.id == window?.activeTabId }
        if (at < 0 || tabs.size < 2) return
        windows.activate(tabs[((at + by) % tabs.size + tabs.size) % tabs.size].id)
    }
}
