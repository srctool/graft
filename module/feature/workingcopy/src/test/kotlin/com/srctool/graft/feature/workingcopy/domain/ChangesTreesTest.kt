// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain

import com.srctool.graft.feature.workingcopy.domain.api.models.ChangeRow
import com.srctool.graft.feature.workingcopy.domain.api.models.ChangesTrees
import com.srctool.graft.feature.workingcopy.domain.api.models.FileNode
import com.srctool.graft.feature.workingcopy.domain.api.models.FolderNode
import com.srctool.graft.feature.workingcopy.domain.api.models.RowStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The tree and the flat list the Changes panel draws. */
class ChangesTreesTest {
    private fun rows(vararg paths: String) = paths.map { ChangeRow(it, RowStatus.MODIFIED) }

    private fun names(nodes: List<com.srctool.graft.feature.workingcopy.domain.api.models.VisibleNode>) =
        nodes.map { "${" ".repeat(it.depth)}${it.node.name}${if (it.expanded == false) "+" else ""}" }

    @Test
    fun `folders come first, then files, each by name ignoring case`() {
        val tree = ChangesTrees.build(rows("b.txt", "A.txt", "z/file", "Y/file", "readme.md"))
        assertEquals(listOf("Y", "z", "A.txt", "b.txt", "readme.md"), tree.map { it.name })
    }

    @Test
    fun `a chain of folders with one folder each is one node`() {
        val tree = ChangesTrees.build(rows("src/main/kotlin/A.kt", "src/main/kotlin/B.kt", "docs/x.md"))
        val src = assertIs<FolderNode>(tree.first { it.path.startsWith("src") })
        assertEquals("src/main/kotlin", src.name)
        assertEquals("src/main/kotlin", src.path)
        assertEquals(2, src.fileCount)
        assertEquals(listOf("A.kt", "B.kt"), src.children.map { it.name })
    }

    @Test
    fun `a folder with files of its own is not merged with the folder inside`() {
        val tree = ChangesTrees.build(rows("src/a.kt", "src/inner/b.kt"))
        val src = assertIs<FolderNode>(tree.single())
        assertEquals("src", src.name)
        assertEquals(listOf("inner", "a.kt"), src.children.map { it.name })
        assertEquals(2, src.fileCount)
    }

    @Test
    fun `visible lists a folder and, when open, what is in it`() {
        val tree = ChangesTrees.build(rows("src/a.kt", "src/inner/b.kt", "top.txt"))
        assertEquals(
            listOf("src", " inner", "  b.kt", " a.kt", "top.txt"),
            names(ChangesTrees.visible(tree, emptySet())),
        )
    }

    @Test
    fun `a folded folder hides its contents and is marked`() {
        val tree = ChangesTrees.build(rows("src/a.kt", "src/inner/b.kt", "top.txt"))
        assertEquals(listOf("src+", "top.txt"), names(ChangesTrees.visible(tree, setOf("src"))))
        assertEquals(
            listOf("src", " inner+", " a.kt", "top.txt"),
            names(ChangesTrees.visible(tree, setOf("src/inner"))),
        )
    }

    @Test
    fun `the flat list is every file by path`() {
        val flat = ChangesTrees.flat(rows("z/b.txt", "a.txt", "z/a.txt"))
        assertEquals(listOf("a.txt", "a.txt", "b.txt"), flat.map { it.node.name })
        assertEquals(listOf("a.txt", "z/a.txt", "z/b.txt"), flat.map { it.node.path })
        assertEquals(setOf(0), flat.map { it.depth }.toSet())
    }

    @Test
    fun `files under a folder, for ticking it`() {
        val tree = ChangesTrees.build(rows("src/a.kt", "src/inner/b.kt", "top.txt"))
        val src = tree.first { it.name == "src" }
        assertEquals(listOf("src/inner/b.kt", "src/a.kt"), ChangesTrees.files(src).map { it.path })
        assertEquals(listOf("top.txt"), ChangesTrees.files(tree.first { it is FileNode }).map { it.path })
    }

    @Test
    fun `no rows, no nodes`() {
        assertEquals(emptyList(), ChangesTrees.build(emptyList()))
        assertEquals(emptyList(), ChangesTrees.flat(emptyList()))
    }
}
