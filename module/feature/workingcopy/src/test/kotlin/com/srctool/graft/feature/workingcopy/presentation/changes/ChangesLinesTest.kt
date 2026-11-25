// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.presentation.changes

import com.srctool.graft.feature.workingcopy.domain.api.models.SectionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChangesLinesTest {
    private val model = Sample.model()

    @Test
    fun `a tree has a header per section, then folders and files`() {
        val lines = panelLines(model, tree = true, collapsed = emptySet())
        assertEquals(
            listOf(
                "section:staged",
                "folder:staged:src/login",
                "file:staged:src/login/Backoff.kt",
                "file:staged:src/login/Login.kt",
                "section:unstaged",
                "folder:unstaged:libs",
                "file:unstaged:libs/engine",
                "folder:unstaged:src/login",
                "file:unstaged:src/login/Login.kt",
                "file:unstaged:src/login/Session.kt",
                "file:unstaged:notes.txt",
            ).sorted(),
            lines.map { it.key }.sorted(),
        )
        assertEquals(2, lines.count { it is PanelLine.Header })
    }

    @Test
    fun `flat has files only, by path`() {
        val lines = panelLines(model, tree = false, collapsed = emptySet())
        assertTrue(lines.none { it is PanelLine.Folder })
        assertEquals(
            listOf("src/login/Backoff.kt", "src/login/Login.kt"),
            lines.filterIsInstance<PanelLine.File>().filter { it.section == SectionKind.Staged }.map { it.row.path },
        )
    }

    @Test
    fun `a folded folder hides what is under it`() {
        val lines = panelLines(model, tree = true, collapsed = setOf("src/login"))
        assertTrue(lines.none { it is PanelLine.File && it.row.path.startsWith("src/login/") })
        assertTrue(lines.filterIsInstance<PanelLine.Folder>().none { it.open && it.node.path == "src/login" })
    }

    @Test
    fun `a file in both lists is partly staged in each`() {
        val files = panelLines(model, tree = false, collapsed = emptySet()).filterIsInstance<PanelLine.File>()
        val login = files.filter { it.row.path == Sample.login.path }
        assertEquals(2, login.size)
        assertTrue(login.all { it.partly })
        assertTrue(files.first { it.row.path == Sample.session.path }.partly.not())
    }

    @Test
    fun `a folder or header stands for every file under it`() {
        val lines = panelLines(model, tree = true, collapsed = emptySet())
        val folder = lines.first {
            it is PanelLine.Folder && it.section == SectionKind.Unstaged &&
                it.node.path == "src/login"
        }
        assertEquals(
            listOf("src/login/Login.kt", "src/login/Session.kt"),
            filesOf(folder, model).map {
                it.path
            }.sorted(),
        )
        val header = lines.first { it is PanelLine.Header && it.section == SectionKind.Staged }
        assertEquals(2, filesOf(header, model).size)
    }
}
