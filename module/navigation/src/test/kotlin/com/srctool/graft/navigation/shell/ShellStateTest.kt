// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.shell

import com.srctool.graft.data.app.api.models.SavedWindow
import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.presentation.window.TabContent
import com.srctool.graft.presentation.window.WindowState
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Navigation with fakes: open, back, a tab switch keeping each tab's stack, and what a window saves and restores. */
class ShellStateTest {
    private var next = 0
    private val windows = WindowState { "id${next++}" }
    private val windowId = windows.openWindow()
    private val gone = setOf(folder("gone"))

    private fun folder(name: String): Path = Paths.get("work").resolve(name).toAbsolutePath()

    private fun location(root: Path) = RepositoryLocation(root, root.resolve(".git"), root.resolve(".git"))

    private val locate: suspend (Path) -> RepositoryLocation? = { if (it in gone) null else location(it) }

    private val shell = ShellState(windows, windowId, locate)

    private fun names() = shell.window!!.tabs.map {
        when (val c = it.content) {
            TabContent.Home -> "Home"
            is TabContent.Repository -> c.root.fileName.toString()
        }
    }

    @Test
    fun `a window starts on Home, and opening a repository gives it a tab in front`() = runBlocking {
        assertEquals(listOf("Home"), names())
        assertTrue(shell.open(folder("app")))
        assertEquals(listOf("Home", "app"), names())
        assertEquals("app", (shell.activeTab!!.content as TabContent.Repository).root.fileName.toString())
    }

    @Test
    fun `opening a repository that's already open brings its tab forward, not a second one`() = runBlocking {
        shell.open(folder("app"))
        shell.open(folder("api"))
        shell.open(folder("app"))
        assertEquals(listOf("Home", "app", "api"), names())
        assertEquals("app", (shell.activeTab!!.content as TabContent.Repository).root.fileName.toString())
    }

    @Test
    fun `a folder that holds no repository changes nothing`() = runBlocking {
        assertFalse(shell.open(folder("gone")))
        assertEquals(listOf("Home"), names())
    }

    @Test
    fun `each tab keeps its own back stack across a tab switch`() = runBlocking {
        shell.open(folder("app"))
        val app = shell.activeTab!!
        shell.open(folder("api"))
        val api = shell.activeTab!!
        shell.push(app.id, TabKey.Home)
        shell.push(app.id, TabKey.Repository("elsewhere"))
        assertEquals(1, shell.stack(api).size)
        shell.activate(api.id)
        shell.activate(app.id)
        assertEquals(3, shell.stack(app).size)
        assertTrue(shell.back(app.id))
        assertTrue(shell.back(app.id))
        assertFalse(shell.back(app.id), "the tab's own screen stays")
        assertEquals(1, shell.stack(api).size)
    }

    @Test
    fun `a tab keeps its panel layout while it lives and the layout goes with it`() = runBlocking {
        shell.open(folder("app"))
        val app = shell.activeTab!!
        val dock = shell.dock(app)
        shell.goHome()
        assertSame(dock, shell.dock(app))
        shell.close(app.id)
        assertEquals(listOf("Home"), names())
        shell.open(folder("app"))
        assertTrue(shell.dock(shell.activeTab!!) !== dock)
    }

    @Test
    fun `closing the tab in front brings its neighbour up, and Home can't be closed`() = runBlocking {
        shell.open(folder("app"))
        shell.open(folder("api"))
        shell.close(shell.activeTab!!.id)
        assertEquals(listOf("Home", "app"), names())
        val home = shell.window!!.tabs.first()
        shell.close(home.id)
        assertEquals(listOf("Home", "app"), names())
    }

    @Test
    fun `next and previous tab wrap around`() = runBlocking {
        shell.open(folder("app"))
        shell.open(folder("api"))
        fun front() = (shell.activeTab!!.content as? TabContent.Repository)?.root?.fileName?.toString() ?: "Home"
        assertEquals("api", front())
        shell.step(1)
        assertEquals("Home", front())
        shell.step(-1)
        assertEquals("api", front())
    }

    @Test
    fun `a window saves its repository tabs and which is in front, then restores them`() = runBlocking {
        shell.open(folder("app"))
        shell.open(folder("api"))
        shell.activate(shell.window!!.tabs.first { (it.content as? TabContent.Repository)?.root == folder("app") }.id)
        val saved = windows.toSaved(windowId, 10, 20, 1200, 800, maximized = false)
        assertEquals(listOf(folder("app").toString(), folder("api").toString()), saved.tabs)
        assertEquals(0, saved.activeTab)

        val fresh = WindowState { "n${next++}" }
        val id = fresh.openWindow()
        assertTrue(fresh.restoreTabs(id, saved, locate).isEmpty())
        val restored = ShellState(fresh, id, locate)
        assertEquals(
            listOf("Home", "app", "api"),
            restored.window!!.tabs.map { (it.content as? TabContent.Repository)?.root?.fileName?.toString() ?: "Home" },
        )
        assertEquals("app", (restored.activeTab!!.content as TabContent.Repository).root.fileName.toString())
    }

    @Test
    fun `a saved folder that's gone is skipped and reported, and the right tab still comes to the front`() =
        runBlocking {
            val saved =
                SavedWindow(0, 0, 900, 700, false, listOf("app", "gone", "api").map { folder(it).toString() }, 2)
            val fresh = WindowState { "m${next++}" }
            val id = fresh.openWindow()
            assertEquals(listOf(folder("gone").toString()), fresh.restoreTabs(id, saved, locate))
            val restored = ShellState(fresh, id, locate)
            assertEquals(3, restored.window!!.tabs.size)
            assertEquals("api", (restored.activeTab!!.content as TabContent.Repository).root.fileName.toString())
        }

    @Test
    fun `when Home was in front, Home is in front after a restore`() = runBlocking {
        val saved = SavedWindow(0, 0, 900, 700, false, listOf(folder("app").toString()), -1)
        val fresh = WindowState { "h${next++}" }
        val id = fresh.openWindow()
        fresh.restoreTabs(id, saved, locate)
        assertEquals(TabContent.Home, ShellState(fresh, id, locate).activeTab!!.content)
    }
}
