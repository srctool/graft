// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.presentation.changes

import com.srctool.graft.feature.workingcopy.domain.api.models.ChangeRow
import com.srctool.graft.feature.workingcopy.domain.api.models.ChangesModel
import com.srctool.graft.feature.workingcopy.domain.api.models.ChangesTrees
import com.srctool.graft.feature.workingcopy.domain.api.models.FileNode
import com.srctool.graft.feature.workingcopy.domain.api.models.FolderNode
import com.srctool.graft.feature.workingcopy.domain.api.models.SectionKind

/** One line of the Changes panel, in the order it is drawn and walked with the arrow keys. */
internal sealed interface PanelLine {
    /** Stable across refreshes, so the cursor and the scroll stay on the same file. */
    val key: String

    data class Header(val section: SectionKind, val count: Int) : PanelLine {
        override val key: String get() = "section:${section.tag}"
    }

    data class Folder(val section: SectionKind, val node: FolderNode, val depth: Int, val open: Boolean) : PanelLine {
        override val key: String get() = "folder:${section.tag}:${node.path}"
    }

    /** [alsoStaged] / [alsoUnstaged]: the same path is in the other list too, so the file is partly staged. */
    data class File(val section: SectionKind, val row: ChangeRow, val depth: Int, val partly: Boolean) : PanelLine {
        override val key: String get() = "file:${section.tag}:${row.path}"
    }
}

internal val SectionKind.tag: String
    get() = when (this) {
        SectionKind.Conflicts -> "conflicts"
        SectionKind.Staged -> "staged"
        SectionKind.Unstaged -> "unstaged"
        is SectionKind.Changelist -> "list-$id"
    }

/** The lines of [model]: a header per section, then its files as a tree (folders folded as in [collapsed]) or flat. */
internal fun panelLines(model: ChangesModel, tree: Boolean, collapsed: Set<String>): List<PanelLine> {
    val staged = model.sections.firstOrNull {
        it.kind == SectionKind.Staged
    }?.rows?.mapTo(HashSet()) { it.path }.orEmpty()
    val unstaged =
        model.sections.firstOrNull { it.kind == SectionKind.Unstaged }?.rows?.mapTo(HashSet()) { it.path }.orEmpty()
    val out = ArrayList<PanelLine>()
    for (section in model.sections) {
        if (section.rows.isEmpty()) continue
        out += PanelLine.Header(section.kind, section.rows.size)
        val partly: (ChangeRow) -> Boolean = {
            when (section.kind) {
                SectionKind.Staged -> it.path in unstaged
                SectionKind.Unstaged -> it.path in staged
                else -> false
            }
        }
        val visible = if (tree) {
            ChangesTrees.visible(
                ChangesTrees.build(section.rows),
                collapsed,
            )
        } else {
            ChangesTrees.flat(section.rows)
        }
        for (line in visible) {
            when (val node = line.node) {
                is FolderNode -> out += PanelLine.Folder(section.kind, node, line.depth, line.expanded == true)
                is FileNode -> out += PanelLine.File(section.kind, node.row, line.depth, partly(node.row))
            }
        }
    }
    return out
}

/** Every file under a line: the file itself, a folder's files, or a whole section's. */
internal fun filesOf(line: PanelLine, model: ChangesModel): List<ChangeRow> = when (line) {
    is PanelLine.File -> listOf(line.row)
    is PanelLine.Folder -> ChangesTrees.files(line.node)
    is PanelLine.Header -> model.sections.firstOrNull { it.kind == line.section }?.rows.orEmpty()
}
