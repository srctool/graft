// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain.api.models

internal sealed interface TreeNode {
    /** From the repository root. A folder chain is one node named by its deepest folder's path. */
    val path: String
    val name: String
}

/** [name] is a single folder or a chain (`src/main/kotlin`) when each folder holds nothing but the next. */
internal data class FolderNode(
    override val path: String,
    override val name: String,
    val children: List<TreeNode>,
    val fileCount: Int,
) : TreeNode

internal data class FileNode(val row: ChangeRow) : TreeNode {
    override val path: String get() = row.path
    override val name: String get() = row.name
}

/** A line of the panel: [expanded] is null for a file. */
internal data class VisibleNode(val node: TreeNode, val depth: Int, val expanded: Boolean?)

/** Folders for the tree view and one list for the flat view. */
internal object ChangesTrees {
    fun build(rows: List<ChangeRow>): List<TreeNode> {
        val root = Dir("")
        for (row in rows) {
            val parts = row.path.split('/')
            var dir = root
            for (part in parts.dropLast(1)) {
                dir =
                    dir.folders.getOrPut(part) { Dir(if (dir.path.isEmpty()) part else "${dir.path}/$part") }
            }
            dir.files += row
        }
        return children(root)
    }

    private class Dir(val path: String) {
        val folders = LinkedHashMap<String, Dir>()
        val files = ArrayList<ChangeRow>()
    }

    private fun children(dir: Dir): List<TreeNode> {
        val folders = dir.folders.entries.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) {
                it.key
            },
        ).map { fold(it.key, it.value) }
        val files = dir.files.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }).map(::FileNode)
        return folders + files
    }

    /** A folder with no files and one folder merges with it (`a` + `b` = `a/b`), as IDEs show a package chain. */
    private fun fold(name: String, dir: Dir): FolderNode {
        var label = name
        var current = dir
        while (current.files.isEmpty() && current.folders.size == 1) {
            val (childName, child) = current.folders.entries.single()
            label = "$label/$childName"
            current = child
        }
        val kids = children(current)
        return FolderNode(current.path, label, kids, kids.sumOf { count(it) })
    }

    private fun count(node: TreeNode): Int = if (node is FolderNode) node.fileCount else 1

    /** The lines to draw: a folded folder hides what is under it. */
    fun visible(nodes: List<TreeNode>, collapsed: Set<String>): List<VisibleNode> {
        val out = ArrayList<VisibleNode>()
        fun walk(list: List<TreeNode>, depth: Int) {
            for (node in list) {
                if (node is FolderNode) {
                    val open = node.path !in collapsed
                    out += VisibleNode(node, depth, open)
                    if (open) walk(node.children, depth + 1)
                } else {
                    out += VisibleNode(node, depth, null)
                }
            }
        }
        walk(nodes, 0)
        return out
    }

    /** The flat view: every file on one level, by path. */
    fun flat(rows: List<ChangeRow>): List<VisibleNode> =
        rows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.path }).map { VisibleNode(FileNode(it), 0, null) }

    /** Every file under [node], for checking or unchecking a folder. */
    fun files(node: TreeNode): List<ChangeRow> = when (node) {
        is FileNode -> listOf(node.row)
        is FolderNode -> node.children.flatMap(::files)
    }
}
