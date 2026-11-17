// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.details

import com.srctool.graft.data.git.api.models.ChangedFile
import java.util.TreeMap

/** A row of a commit's changed files: a folder (tree view only) or a file. */
internal sealed interface FileRow {
    val key: String
    val depth: Int

    data class Folder(val path: String, val name: String, override val depth: Int, val expanded: Boolean) : FileRow {
        override val key: String get() = "folder:$path"
    }

    /** [label] is the name in a folder, or the whole path in the list. */
    data class File(val file: ChangedFile, val label: String, override val depth: Int) : FileRow {
        override val key: String get() = "file:${file.path}"
    }
}

/**
 * [files] as rows (S5): the whole paths in git's order for the list, or a tree of folders, folders first and each
 * level by name, where a folder with a single child folder is joined with it (), as IDEs show it.
 */
internal fun fileRows(files: List<ChangedFile>, tree: Boolean, collapsed: Set<String> = emptySet()): List<FileRow> {
    if (!tree) return files.map { FileRow.File(it, it.path, 0) }
    class Node(val folders: TreeMap<String, Node> = TreeMap(), val files: MutableList<ChangedFile> = ArrayList())
    val root = Node()
    for (file in files) {
        val parts = file.path.split('/')
        var node = root
        for (part in parts.dropLast(1)) node = node.folders.getOrPut(part) { Node() }
        node.files += file
    }
    val rows = ArrayList<FileRow>()
    fun walk(node: Node, path: String, depth: Int) {
        for ((name, child) in node.folders) {
            // Join single-folder chains: src/main/kotlin is one row.
            var label = name
            var folder = child
            var folderPath = if (path.isEmpty()) name else "$path/$name"
            while (folder.files.isEmpty() && folder.folders.size == 1) {
                val (next, nextNode) = folder.folders.firstEntry()
                label += "/$next"
                folderPath += "/$next"
                folder = nextNode
            }
            val row = FileRow.Folder(folderPath, label, depth, expanded = "folder:$folderPath" !in collapsed)
            rows += row
            if (row.expanded) walk(folder, folderPath, depth + 1)
        }
        node.files.sortedBy { it.path.substringAfterLast('/') }.forEach {
            rows +=
                FileRow.File(it, it.path.substringAfterLast('/'), depth)
        }
    }
    walk(root, "", 0)
    return rows
}
