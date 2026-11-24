// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain.api.models

import com.srctool.graft.data.app.api.models.ChangelistBook
import com.srctool.graft.data.app.api.models.StoredHunk
import com.srctool.graft.data.git.api.models.ChangeEntry
import com.srctool.graft.data.git.api.models.ChangeStatus
import com.srctool.graft.data.git.api.models.HunkAnchor
import com.srctool.graft.data.git.api.models.WorkingBlocks
import com.srctool.graft.data.git.api.models.WorkingTree

/** The panel's lists made from `git status`, in either mode (Working Copy and Commit; Changelists, "Mode"). */
internal object ChangesModels {
    /**
     * Staging-area mode: conflicts, then Staged, then Unstaged (changed files and untracked ones). A file changed in
     * both the index and the working tree is in both lists. Ignored paths are not shown. Empty sections are left out.
     */
    fun staging(workingCopy: WorkingCopy, tree: WorkingTree): ChangesModel {
        val sections =
            listOf(
                ChangesSection(SectionKind.Conflicts, conflicts(tree)),
                ChangesSection(SectionKind.Staged, tree.staged.map { row(it, it.index) }.sorted()),
                ChangesSection(
                    SectionKind.Unstaged,
                    (tree.unstaged.map { row(it, it.worktree) } + tree.untracked.map { row(it, null) }).sorted(),
                ),
            )
        return ChangesModel(workingCopy, sections.filter { it.rows.isNotEmpty() })
    }

    /**
     * Changelists mode: conflicts, then one section per list in the book's order (all of them, even when empty), each
     * holding its files and the hunks it owns. [blocks] are the working tree's hunks against `HEAD`; a hunk the book
     * has no owner for, or a path it has no assignment for, belongs to the active list (the next sync stores it).
     */
    fun changelists(
        workingCopy: WorkingCopy,
        tree: WorkingTree,
        book: ChangelistBook,
        blocks: WorkingBlocks,
    ): ChangesModel {
        val byPath = HashMap<String, ChangeEntry>()
        for (entry in tree.entries) if (entry.conflict == null && !entry.ignored) byPath[entry.path] = entry
        val perList = LinkedHashMap<String, MutableList<ChangeRow>>()
        book.lists.forEach { perList[it.id] = ArrayList() }

        val hunked = HashSet<String>()
        for (file in blocks.files) {
            val entry = byPath[file.path] ?: continue
            hunked += file.path
            // Each stored hunk is matched once, so two identical hunks are two hunks.
            val unused = book.hunks.filter { it.path == file.path }.toMutableList()
            val ids = LinkedHashMap<String, MutableSet<Int>>()
            for (hunk in file.hunks) {
                val match = unused.firstOrNull { it.hunk == hunk.anchor.toStored() }
                if (match != null) unused -= match
                val owner = match?.listId?.takeIf { perList.containsKey(it) } ?: book.activeId
                ids.getOrPut(owner) { LinkedHashSet() } += hunk.id
            }
            for ((owner, set) in ids) {
                perList.getValue(owner) +=
                    row(entry, null).copy(hunks = set, hunkCount = file.hunks.size, status = changeStatus(entry))
            }
        }
        for (entry in byPath.values) {
            if (entry.path in hunked) continue
            val assigned = book.files.firstOrNull { it.path == entry.path }?.listId
            val owner = assigned?.takeIf { perList.containsKey(it) } ?: book.activeId
            perList.getValue(owner) += row(entry, null).copy(status = changeStatus(entry))
        }
        val lists =
            book.lists.map {
                ChangesSection(
                    SectionKind.Changelist(it.id, it.name, it.id == book.activeId),
                    perList.getValue(it.id).sorted(),
                )
            }
        val conflicts = ChangesSection(SectionKind.Conflicts, conflicts(tree))
        return ChangesModel(workingCopy, (listOf(conflicts).filter { it.rows.isNotEmpty() }) + lists)
    }

    private fun conflicts(tree: WorkingTree): List<ChangeRow> =
        tree.conflicted.map { ChangeRow(it.path, RowStatus.CONFLICT, conflict = it.conflict) }.sorted()

    /** Against `HEAD`, as a changelist sees a file: a deletion wins, then what the index says, then the working tree. */
    private fun changeStatus(entry: ChangeEntry): RowStatus = when {
        entry.untracked -> RowStatus.UNTRACKED
        entry.worktree == ChangeStatus.DELETED -> RowStatus.DELETED
        else -> status(entry.index ?: entry.worktree)
    }

    private fun row(entry: ChangeEntry, side: ChangeStatus?): ChangeRow = ChangeRow(
        path = entry.path,
        status = if (entry.untracked) RowStatus.UNTRACKED else status(side),
        originalPath = entry.originalPath,
        submodule = entry.submodule,
        modeChanged = entry.modes?.let { if (side == entry.index) it.stagedChange else it.unstagedChange } ?: false,
    )

    private fun status(side: ChangeStatus?): RowStatus = when (side) {
        ChangeStatus.ADDED -> RowStatus.ADDED
        ChangeStatus.DELETED -> RowStatus.DELETED
        ChangeStatus.RENAMED -> RowStatus.RENAMED
        ChangeStatus.COPIED -> RowStatus.COPIED
        ChangeStatus.TYPE_CHANGED -> RowStatus.TYPE_CHANGED
        ChangeStatus.MODIFIED, null -> RowStatus.MODIFIED
    }

    private fun List<ChangeRow>.sorted(): List<ChangeRow> = sortedWith(
        compareBy(String.CASE_INSENSITIVE_ORDER) {
            it.path
        },
    )
}

/** The data layer's hunk fingerprint and the app layer's stored copy of it are the same fields. */
internal fun HunkAnchor.toStored(): StoredHunk = StoredHunk(oldStart, oldCount, change, removed, base, removedCount)

internal fun StoredHunk.toAnchor(): HunkAnchor = HunkAnchor(oldStart, oldCount, change, removed, base, removedCount)
