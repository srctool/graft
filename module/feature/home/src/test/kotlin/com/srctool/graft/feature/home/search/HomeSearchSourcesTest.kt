// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.search

import com.srctool.graft.data.app.api.models.KnownRepository
import com.srctool.graft.feature.home.presentation.FakeKnown
import com.srctool.graft.presentation.search.SearchKind
import com.srctool.graft.presentation.search.SearchScope
import com.srctool.graft.presentation.search.SearchTarget
import kotlinx.coroutines.runBlocking
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeSearchSourcesTest {
    private val home = Paths.get("/home/ann")
    private val repos =
        (1..7).map {
            KnownRepository(home.resolve("Projects/r$it"), lastOpened = it * 100L, pinned = false, missing = false)
        } +
            KnownRepository(home.resolve("gone"), lastOpened = 9_999, pinned = false, missing = true) +
            KnownRepository(Paths.get("/srv/other"), lastOpened = 50, pinned = false, missing = false)
    private val source = RepositorySearchSource(FakeKnown(repos), home)

    @Test
    fun `before typing, the five most recently opened repositories show, never a missing one`() = runBlocking {
        val found = source.candidates("", SearchScope())
        assertEquals(listOf("r7", "r6", "r5", "r4", "r3"), found.map { it.title })
    }

    @Test
    fun `a search offers every repository that's there, named by folder and shown under ~`() = runBlocking {
        val found = source.candidates("r", SearchScope())
        assertEquals(8, found.size)
        val r7 = found.first { it.title == "r7" }
        assertEquals("~/Projects/r7", r7.detail)
        assertEquals(home.resolve("Projects/r7").toString(), r7.extra)
        assertEquals(SearchTarget.OpenRepository(home.resolve("Projects/r7")), r7.target)
        assertEquals(SearchKind.Repository, r7.kind)
        // Outside the home folder the path stays whole.
        assertEquals(Paths.get("/srv/other").toString(), found.first { it.title == "other" }.detail)
    }

    @Test
    fun `a list that can't be read fails the group`() = runBlocking<Unit> {
        val failing = FakeKnown(repos).also { it.listFails = true }
        val thrown = runCatching {
            RepositorySearchSource(failing, home).candidates("r", SearchScope())
        }.exceptionOrNull()
        assertTrue(thrown != null)
    }

    @Test
    fun `Home's actions offer only those with a shortcut before typing, and all of them after`() = runBlocking {
        val actions = HomeActionSearchSource()
        assertEquals(
            listOf("Open repository…", "Clone repository…"),
            actions.candidates("", SearchScope()).map {
                it.title
            },
        )
        val all = actions.candidates("c", SearchScope())
        assertEquals(5, all.size)
        assertTrue(all.all { it.kind == SearchKind.Action })
        assertEquals(SearchTarget.RunAction(HomeActions.CLONE), all.first { it.title.startsWith("Clone") }.target)
    }

    @Test
    fun `each action id maps to a command, and anyone else's doesn't`() {
        assertEquals(HomeCommand.Open, HomeActions.command(HomeActions.OPEN))
        assertEquals(HomeCommand.NewWorkspace, HomeActions.command(HomeActions.NEW_WORKSPACE))
        assertNull(HomeActions.command("host.settings"))
    }
}
