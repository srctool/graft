// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain

import com.srctool.graft.data.app.api.models.ChangelistBook
import com.srctool.graft.data.app.api.models.FileAssignment
import com.srctool.graft.data.app.api.models.HunkAssignment
import com.srctool.graft.data.git.api.models.ChangeEntry
import com.srctool.graft.data.git.api.models.ChangeStatus
import com.srctool.graft.data.git.api.models.ConflictKind
import com.srctool.graft.data.git.api.models.FileBlocks
import com.srctool.graft.data.git.api.models.FileModes
import com.srctool.graft.data.git.api.models.HunkAnchor
import com.srctool.graft.data.git.api.models.WholeChange
import com.srctool.graft.data.git.api.models.WorkingBlocks
import com.srctool.graft.data.git.api.models.WorkingHunk
import com.srctool.graft.data.git.api.models.WorkingTree
import com.srctool.graft.feature.workingcopy.domain.api.models.ChangesModels
import com.srctool.graft.feature.workingcopy.domain.api.models.RowStatus
import com.srctool.graft.feature.workingcopy.domain.api.models.SectionKind
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingCopy
import com.srctool.graft.feature.workingcopy.domain.api.models.toStored
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The lists the panel shows, in staging-area mode and in changelists mode. */
class ChangesModelsTest {
    private val copy = WorkingCopy(Paths.get("/repo"), "repo")
    private val modes = FileModes("100644", "100644", "100644")

    private fun tree(vararg entries: ChangeEntry) = WorkingTree(entries.toList())

    private fun anchor(tag: String, start: Int = 1) =
        HunkAnchor(start, 3, "change-$tag", "removed-$tag", "base-$tag", 1)

    private fun hunk(id: Int, tag: String) = WorkingHunk(id, anchor(tag, id * 10 + 1), 1, 1)

    // ---- staging area

    @Test
    fun `staged, unstaged and untracked paths go in their lists, and a path changed in both is in both`() {
        val model =
            ChangesModels.staging(
                copy,
                tree(
                    ChangeEntry(
                        "b.txt",
                        index = ChangeStatus.MODIFIED,
                        worktree = ChangeStatus.MODIFIED,
                        modes = modes,
                    ),
                    ChangeEntry("a.txt", index = ChangeStatus.ADDED, modes = modes),
                    ChangeEntry("c.txt", worktree = ChangeStatus.DELETED, modes = modes),
                    ChangeEntry("new.txt", untracked = true),
                    ChangeEntry("ignored.log", ignored = true),
                ),
            )
        assertEquals(listOf(SectionKind.Staged, SectionKind.Unstaged), model.sections.map { it.kind })
        val (staged, unstaged) = model.sections
        assertEquals(
            listOf("a.txt" to RowStatus.ADDED, "b.txt" to RowStatus.MODIFIED),
            staged.rows.map {
                it.path to
                    it.status
            },
        )
        assertEquals(
            listOf("b.txt" to RowStatus.MODIFIED, "c.txt" to RowStatus.DELETED, "new.txt" to RowStatus.UNTRACKED),
            unstaged.rows.map { it.path to it.status },
        )
        assertEquals(setOf("a.txt", "b.txt", "c.txt", "new.txt"), model.paths)
    }

    @Test
    fun `conflicts come first and are in no other list`() {
        val model =
            ChangesModels.staging(
                copy,
                tree(
                    ChangeEntry("z.txt", conflict = ConflictKind.BOTH_MODIFIED),
                    ChangeEntry("a.txt", worktree = ChangeStatus.MODIFIED, modes = modes),
                ),
            )
        assertEquals(listOf(SectionKind.Conflicts, SectionKind.Unstaged), model.sections.map { it.kind })
        assertEquals(RowStatus.CONFLICT, model.sections[0].rows.single().status)
        assertEquals(ConflictKind.BOTH_MODIFIED, model.sections[0].rows.single().conflict)
    }

    @Test
    fun `a rename keeps its old path, and a mode change is flagged on the side it is on`() {
        val model =
            ChangesModels.staging(
                copy,
                tree(
                    ChangeEntry("new.kt", originalPath = "old.kt", index = ChangeStatus.RENAMED, modes = modes),
                    ChangeEntry(
                        "run.sh",
                        worktree = ChangeStatus.MODIFIED,
                        modes = FileModes("100644", "100644", "100755"),
                    ),
                ),
            )
        val staged = model.sections.single { it.kind == SectionKind.Staged }.rows.single()
        assertEquals("old.kt", staged.originalPath)
        assertEquals(RowStatus.RENAMED, staged.status)
        val unstaged = model.sections.single { it.kind == SectionKind.Unstaged }.rows.single()
        assertTrue(unstaged.modeChanged)
    }

    @Test
    fun `a clean working tree has no sections`() {
        val model = ChangesModels.staging(copy, tree())
        assertTrue(model.isEmpty)
        assertEquals(emptyList(), model.sections)
    }

    // ---- changelists

    private val agent = "agent"
    private val book = ChangelistBook().create(agent, "Agent")

    @Test
    fun `every list is a section, even an empty one, and new files land in the active list`() {
        val model =
            ChangesModels.changelists(
                copy,
                tree(
                    ChangeEntry("new.txt", untracked = true),
                    ChangeEntry("gone.txt", worktree = ChangeStatus.DELETED, modes = modes),
                ),
                book.activate(agent),
                WorkingBlocks(
                    emptyList(),
                    listOf(WholeChange("new.txt", null, true), WholeChange("gone.txt", null, false)),
                ),
            )
        assertEquals(
            listOf(
                SectionKind.Changelist("default", "Default", active = false),
                SectionKind.Changelist(agent, "Agent", active = true),
            ),
            model.sections.map { it.kind },
        )
        assertEquals(emptyList(), model.sections[0].rows)
        assertEquals(listOf("gone.txt", "new.txt"), model.sections[1].rows.map { it.path })
        assertEquals(RowStatus.DELETED, model.sections[1].rows[0].status)
        assertEquals(RowStatus.UNTRACKED, model.sections[1].rows[1].status)
    }

    @Test
    fun `a file follows its assignment`() {
        val assigned = book.copy(files = listOf(FileAssignment("a.txt", agent)))
        val model =
            ChangesModels.changelists(
                copy,
                tree(ChangeEntry("a.txt", worktree = ChangeStatus.ADDED, index = ChangeStatus.ADDED, modes = modes)),
                assigned,
                WorkingBlocks(emptyList(), listOf(WholeChange("a.txt", null, false))),
            )
        assertEquals(emptyList(), model.sections[0].rows)
        assertEquals(listOf("a.txt"), model.sections[1].rows.map { it.path })
        assertEquals(null, model.sections[1].rows.single().hunks)
    }

    @Test
    fun `a file split between lists is a row in each, with its hunks`() {
        val hunks = listOf(hunk(0, "x"), hunk(1, "y"), hunk(2, "z"))
        val assigned =
            book.copy(
                hunks =
                listOf(
                    HunkAssignment("a.kt", "default", hunks[0].anchor.toStored()),
                    HunkAssignment("a.kt", agent, hunks[1].anchor.toStored()),
                    HunkAssignment("a.kt", "default", hunks[2].anchor.toStored()),
                ),
            )
        val model =
            ChangesModels.changelists(
                copy,
                tree(ChangeEntry("a.kt", worktree = ChangeStatus.MODIFIED, modes = modes)),
                assigned,
                WorkingBlocks(listOf(FileBlocks("a.kt", null, hunks)), emptyList()),
            )
        val default = model.sections[0].rows.single()
        assertEquals(setOf(0, 2), default.hunks)
        assertEquals(3, default.hunkCount)
        assertTrue(default.partial)
        val other = model.sections[1].rows.single()
        assertEquals(setOf(1), other.hunks)
        assertTrue(other.partial)
    }

    @Test
    fun `a hunk nobody owns belongs to the active list`() {
        val model =
            ChangesModels.changelists(
                copy,
                tree(ChangeEntry("a.kt", worktree = ChangeStatus.MODIFIED, modes = modes)),
                book.activate(agent),
                WorkingBlocks(listOf(FileBlocks("a.kt", null, listOf(hunk(0, "x")))), emptyList()),
            )
        assertEquals(emptyList(), model.sections[0].rows)
        val row = model.sections[1].rows.single()
        assertEquals(setOf(0), row.hunks)
        assertEquals(false, row.partial)
    }

    @Test
    fun `two identical hunks are two hunks, each matched once`() {
        val same = anchor("same")
        val twin = listOf(WorkingHunk(0, same, 1, 1), WorkingHunk(1, same, 1, 1))
        val assigned =
            book.copy(
                hunks =
                listOf(
                    HunkAssignment("a.kt", agent, same.toStored()),
                    HunkAssignment("a.kt", "default", same.toStored()),
                ),
            )
        val model =
            ChangesModels.changelists(
                copy,
                tree(ChangeEntry("a.kt", worktree = ChangeStatus.MODIFIED, modes = modes)),
                assigned,
                WorkingBlocks(listOf(FileBlocks("a.kt", null, twin)), emptyList()),
            )
        assertEquals(setOf(1), model.sections[0].rows.single().hunks)
        assertEquals(setOf(0), model.sections[1].rows.single().hunks)
    }

    @Test
    fun `a list that no longer exists sends its changes to the active list`() {
        val assigned = book.copy(files = listOf(FileAssignment("a.txt", "deleted-list")))
        val model =
            ChangesModels.changelists(
                copy,
                tree(ChangeEntry("a.txt", untracked = true)),
                assigned,
                WorkingBlocks(emptyList(), listOf(WholeChange("a.txt", null, true))),
            )
        assertEquals(listOf("a.txt"), model.sections[0].rows.map { it.path })
    }

    @Test
    fun `conflicts are above the lists and in none of them`() {
        val model =
            ChangesModels.changelists(
                copy,
                tree(ChangeEntry("c.txt", conflict = ConflictKind.BOTH_ADDED)),
                book,
                WorkingBlocks(emptyList(), emptyList()),
            )
        assertEquals(SectionKind.Conflicts, model.sections.first().kind)
        assertEquals(listOf("c.txt"), model.sections.first().rows.map { it.path })
        assertTrue(model.sections.drop(1).all { it.rows.isEmpty() })
    }
}
