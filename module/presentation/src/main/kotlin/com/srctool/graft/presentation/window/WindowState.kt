// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.presentation.window

import com.srctool.graft.data.git.api.models.RepositoryLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.nio.file.Path
import java.util.UUID

/** What a tab shows: Home, or one repository (a worktree and a submodule are repositories of their own). */
sealed interface TabContent {
    data object Home : TabContent

    data class Repository(val location: RepositoryLocation) : TabContent {
        /** The folder the tab is known by: the working tree, or a bare repository's git folder. */
        val root: Path get() = location.workTree ?: location.gitDir
    }
}

data class Tab(val id: String, val content: TabContent)

/** One window's tabs, in order, and the one in front; a window always has at least one tab. */
data class AppWindow(val id: String, val tabs: List<Tab>, val activeTabId: String) {
    val activeTab: Tab get() = tabs.first { it.id == activeTabId }
}

/**
 * Graft's windows and their tabs (Repositories and Workspaces, "Tabs and windows"). Every change is one atomic update
 * of [windows], so observers never see a tab in two windows or a window without an active tab.
 */
class WindowState(private val newId: () -> String = { UUID.randomUUID().toString() }) {
    private val state = MutableStateFlow<List<AppWindow>>(emptyList())

    /** The windows, the first being the one Graft opened at start. */
    val windows: StateFlow<List<AppWindow>> = state.asStateFlow()

    /** Every repository some tab shows; the app's repository sessions follow this set. */
    val openRepositories: Flow<Set<RepositoryLocation>> =
        state
            .map { windows ->
                windows.flatMap { it.tabs }.mapNotNull { (it.content as? TabContent.Repository)?.location }.toSet()
            }.distinctUntilChanged()

    /** A new window showing Home; returns its id. */
    fun openWindow(): String {
        val window = AppWindow(newId(), listOf(Tab(newId(), TabContent.Home)), "")
        val opened = window.copy(activeTabId = window.tabs.single().id)
        state.update { it + opened }
        return opened.id
    }

    /**
     * Brings [location] to the front: the tab that already shows it, in whichever window (the same repository is
     * never open twice), or a new tab in [windowId]. Returns the tab's id.
     */
    fun openRepository(windowId: String, location: RepositoryLocation): String {
        val content = TabContent.Repository(location)
        var tabId = ""
        state.update { windows ->
            val existing = windows.firstNotNullOfOrNull { w ->
                w.tabs.firstOrNull { it.content == content }?.let {
                    w to
                        it
                }
            }
            if (existing != null) {
                tabId = existing.second.id
                windows.map { if (it.id == existing.first.id) it.copy(activeTabId = tabId) else it }
            } else {
                val tab = Tab(newId(), content)
                tabId = tab.id
                windows.map { if (it.id == windowId) it.copy(tabs = it.tabs + tab, activeTabId = tab.id) else it }
            }
        }
        return tabId
    }

    /** A Home tab at the end of [windowId], in front. */
    fun openHome(windowId: String): String {
        val tab = Tab(newId(), TabContent.Home)
        state.update { windows ->
            windows.map { if (it.id == windowId) it.copy(tabs = it.tabs + tab, activeTabId = tab.id) else it }
        }
        return tab.id
    }

    /** Brings [tabId] to the front of its window. */
    fun activate(tabId: String) {
        state.update { windows ->
            windows.map { w -> if (w.tabs.any { it.id == tabId }) w.copy(activeTabId = tabId) else w }
        }
    }

    /**
     * Closes [tabId]. Its neighbour comes to the front (the one after it, else the one before); closing a window's last
     * tab leaves Home in it, since a window always shows something.
     */
    fun closeTab(tabId: String) {
        state.update { windows ->
            windows.map { w ->
                val index = w.tabs.indexOfFirst { it.id == tabId }
                if (index < 0) return@map w
                val rest = w.tabs.filterNot { it.id == tabId }
                when {
                    rest.isEmpty() -> {
                        val home = Tab(newId(), TabContent.Home)
                        w.copy(tabs = listOf(home), activeTabId = home.id)
                    }

                    w.activeTabId != tabId -> w.copy(tabs = rest)

                    else -> w.copy(tabs = rest, activeTabId = rest[minOf(index, rest.lastIndex)].id)
                }
            }
        }
    }

    /** Closes [windowId] and every tab in it. */
    fun closeWindow(windowId: String) {
        state.update { windows -> windows.filterNot { it.id == windowId } }
    }

    /** The repositories [windowId]'s tabs show. */
    fun repositoriesIn(windowId: String): List<RepositoryLocation> =
        state.value.firstOrNull { it.id == windowId }?.tabs.orEmpty().mapNotNull {
            (it.content as? TabContent.Repository)?.location
        }
}
