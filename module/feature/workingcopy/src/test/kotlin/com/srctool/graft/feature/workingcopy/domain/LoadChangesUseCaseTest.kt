// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain

import com.srctool.graft.data.app.api.models.ChangelistBook
import com.srctool.graft.data.app.api.models.ChangesMode
import com.srctool.graft.data.app.api.models.ChangesView
import com.srctool.graft.data.app.api.models.FileAssignment
import com.srctool.graft.data.app.api.models.HunkAssignment
import com.srctool.graft.data.git.api.models.ChangeEntry
import com.srctool.graft.data.git.api.models.ChangeStatus
import com.srctool.graft.data.git.api.models.FileBlocks
import com.srctool.graft.data.git.api.models.FileModes
import com.srctool.graft.data.git.api.models.Head
import com.srctool.graft.data.git.api.models.HunkAnchor
import com.srctool.graft.data.git.api.models.WholeChange
import com.srctool.graft.data.git.api.models.WorkingBlocks
import com.srctool.graft.data.git.api.models.WorkingHunk
import com.srctool.graft.data.git.api.models.WorkingTree
import com.srctool.graft.feature.workingcopy.domain.api.models.SectionKind
import com.srctool.graft.feature.workingcopy.domain.api.models.toStored
import com.srctool.graft.feature.workingcopy.domain.api.usecases.ChangelistNotice
import com.srctool.graft.feature.workingcopy.domain.api.usecases.DraftKey
import com.srctool.graft.feature.workingcopy.domain.implementation.usecases.FileDraftsUseCase
import com.srctool.graft.feature.workingcopy.domain.implementation.usecases.FilePanelStateUseCase
import com.srctool.graft.feature.workingcopy.domain.implementation.usecases.GitLoadChangesUseCase
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The panel's content in both modes, with the changelist book kept in line with the working tree. */
class LoadChangesUseCaseTest {
    private val modes = FileModes("100644", "100644", "100644")

    private fun anchor(tag: String, start: Int) = HunkAnchor(start, 3, "change-$tag", "removed-$tag", "base-$tag", 1)

    private fun file(path: String, vararg hunks: WorkingHunk) = FileBlocks(path, null, hunks.toList())

    private fun hunk(id: Int, tag: String, start: Int) = WorkingHunk(id, anchor(tag, start), 1, 1)

    private val modified = ChangeEntry("a.kt", worktree = ChangeStatus.MODIFIED, modes = modes)

    private class Rig(tree: WorkingTree, blocks: WorkingBlocks, book: ChangelistBook = ChangelistBook()) {
        val tree = FakeWorkingTree(Result.Success(tree))
        val blocks = FakeBlocks(Result.Success(blocks))
        val books = FakeChangelists(book)
        val useCase = GitLoadChangesUseCase(this.tree, this.blocks, FakeAccess(), books)
    }

    @Test
    fun `staging mode reads the status and nothing else`() = runTest {
        val rig = Rig(WorkingTree(listOf(modified)), WorkingBlocks(emptyList(), emptyList()))
        val unsupportedBlocks = GitLoadChangesUseCase(rig.tree, FakeBlocks(failure()), FakeAccess(), FakeChangelists())
        val load = unsupportedBlocks.load(COPY, ChangesMode.STAGING_AREA).getOrThrow()
        assertEquals(listOf(SectionKind.Unstaged), load.model.sections.map { it.kind })
        assertEquals(null, load.book)
        assertEquals(emptyList(), load.notices)
    }

    @Test
    fun `a status failure is passed on`() = runTest {
        val rig = Rig(WorkingTree(emptyList()), WorkingBlocks(emptyList(), emptyList()))
        rig.tree.tree = failure("STAT")
        assertIs<Result.Failure>(rig.useCase.load(COPY, ChangesMode.STAGING_AREA))
        assertIs<Result.Failure>(rig.useCase.load(COPY, ChangesMode.CHANGELISTS))
    }

    @Test
    fun `changelists mode stores new changes in the active list and then leaves the file alone`() = runTest {
        val agent = ChangelistBook().create("agent", "Agent").activate("agent")
        val rig =
            Rig(
                WorkingTree(listOf(modified, ChangeEntry("new.txt", untracked = true))),
                WorkingBlocks(listOf(file("a.kt", hunk(0, "x", 5))), listOf(WholeChange("new.txt", null, true))),
                agent,
            )
        val first = rig.useCase.load(COPY, ChangesMode.CHANGELISTS).getOrThrow()
        assertEquals(1, rig.books.writes)
        assertEquals(
            listOf("a.kt", "new.txt"),
            first.model.sections.single {
                (it.kind as? SectionKind.Changelist)?.id ==
                    "agent"
            }.rows.map { it.path },
        )
        assertEquals(listOf(HunkAssignment("a.kt", "agent", anchor("x", 5).toStored())), rig.books.book.hunks)
        assertEquals(listOf(FileAssignment("new.txt", "agent")), rig.books.book.files)

        rig.useCase.load(COPY, ChangesMode.CHANGELISTS).getOrThrow()
        assertEquals(1, rig.books.writes, "a refresh that finds the same lists writes nothing")
    }

    @Test
    fun `a hunk keeps its list when the file above it grew`() = runTest {
        val book = ChangelistBook().create("agent", "Agent")
        val moved = book.copy(hunks = listOf(HunkAssignment("a.kt", "agent", anchor("x", 5).toStored())))
        // Same lines, later in the old file: HEAD moved, or the hunk's old side did.
        val rig =
            Rig(
                WorkingTree(listOf(modified)),
                WorkingBlocks(listOf(file("a.kt", hunk(0, "x", 25))), emptyList()),
                moved,
            )
        val load = rig.useCase.load(COPY, ChangesMode.CHANGELISTS).getOrThrow()
        assertEquals(
            listOf("a.kt"),
            load.model.sections.single {
                (it.kind as? SectionKind.Changelist)?.id == "agent"
            }.rows.map { it.path },
        )
        assertEquals(emptyList(), load.notices)
        assertEquals(25, rig.books.book.hunks.single().hunk.oldStart)
        assertEquals("agent", rig.books.book.hunks.single().listId)
    }

    @Test
    fun `a hunk edited past recognition moves to the active list with a notice`() = runTest {
        val book = ChangelistBook().create("agent", "Agent")
        val before = book.copy(hunks = listOf(HunkAssignment("a.kt", "agent", anchor("old", 5).toStored())))
        val rig =
            Rig(
                WorkingTree(listOf(modified)),
                WorkingBlocks(listOf(file("a.kt", hunk(0, "new", 15))), emptyList()),
                before,
            )
        val load = rig.useCase.load(COPY, ChangesMode.CHANGELISTS).getOrThrow()
        assertEquals(listOf(ChangelistNotice.HunkMoved("a.kt", "agent")), load.notices)
        assertEquals("default", rig.books.book.hunks.single().listId)
        assertEquals(
            listOf("a.kt"),
            load.model.sections.single {
                (it.kind as? SectionKind.Changelist)?.id == "default"
            }.rows.map { it.path },
        )
    }

    @Test
    fun `changes that are gone are forgotten`() = runTest {
        val book = ChangelistBook().create("agent", "Agent")
        val before =
            book.copy(
                hunks = listOf(HunkAssignment("gone.kt", "agent", anchor("g", 1).toStored())),
                files = listOf(FileAssignment("deleted.txt", "agent")),
            )
        val rig = Rig(WorkingTree(emptyList()), WorkingBlocks(emptyList(), emptyList()), before)
        rig.useCase.load(COPY, ChangesMode.CHANGELISTS).getOrThrow()
        assertEquals(emptyList(), rig.books.book.hunks)
        assertEquals(emptyList(), rig.books.book.files)
    }

    @Test
    fun `a renamed file keeps its list`() = runTest {
        val book = ChangelistBook().create("agent", "Agent").copy(files = listOf(FileAssignment("old.txt", "agent")))
        val rig =
            Rig(
                WorkingTree(
                    listOf(
                        ChangeEntry("new.txt", originalPath = "old.txt", index = ChangeStatus.RENAMED, modes = modes),
                    ),
                ),
                WorkingBlocks(emptyList(), listOf(WholeChange("new.txt", "old.txt", false))),
                book,
            )
        rig.useCase.load(COPY, ChangesMode.CHANGELISTS).getOrThrow()
        assertEquals(listOf(FileAssignment("new.txt", "agent")), rig.books.book.files)
    }

    @Test
    fun `observe loads at once and again for each refresh`() = runTest {
        val rig = Rig(WorkingTree(listOf(modified)), WorkingBlocks(emptyList(), emptyList()))
        val seen = rig.useCase.observe(COPY, ChangesMode.STAGING_AREA, flowOf(Unit, Unit)).toList()
        assertEquals(3, seen.size)
        assertEquals(3, rig.tree.reads)
        assertTrue(seen.all { it is Result.Success })
    }

    // ---- panel state, mode and drafts

    @Test
    fun `tree, folded folders and the changes view are kept per repository`() = runTest {
        val state = FakeUiState()
        val panel = FilePanelStateUseCase(FakeAccess(), state)
        assertEquals(true, panel.read(COPY).getOrThrow().tree)
        panel.setTree(COPY, false).getOrThrow()
        panel.toggleFolder(COPY, "src").getOrThrow()
        panel.toggleFolder(COPY, "docs").getOrThrow()
        assertEquals(setOf("src", "docs"), panel.toggleFolder(COPY, "x").getOrThrow().collapsed - "x")
        val after = panel.toggleFolder(COPY, "src").getOrThrow()
        assertEquals(setOf("docs", "x"), after.collapsed)
        assertEquals(false, after.tree)
    }

    @Test
    fun `the repository's choice beats the app default`() = runTest {
        val panel = FilePanelStateUseCase(FakeAccess(), FakeUiState())
        assertEquals(ChangesMode.CHANGELISTS, panel.mode(COPY, ChangesMode.CHANGELISTS).getOrThrow())
        panel.setView(COPY, ChangesView.STAGING_AREA).getOrThrow()
        assertEquals(ChangesMode.STAGING_AREA, panel.mode(COPY, ChangesMode.CHANGELISTS).getOrThrow())
        panel.setView(COPY, ChangesView.INHERIT).getOrThrow()
        assertEquals(ChangesMode.CHANGELISTS, panel.mode(COPY, ChangesMode.CHANGELISTS).getOrThrow())
    }

    @Test
    fun `the draft is kept per branch in staging mode and per list in changelists mode`() = runTest {
        val access = FakeAccess()
        val books = FakeChangelists(ChangelistBook().create("agent", "Agent").activate("agent"))
        val drafts = FileDraftsUseCase(access, FakeUiState(), books)

        val main = drafts.key(COPY, ChangesMode.STAGING_AREA).getOrThrow()
        assertEquals(DraftKey.Branch("refs/heads/main"), main)
        drafts.write(COPY, main, "On main").getOrThrow()
        access.head = Head.Branch("feature", null)
        val feature = drafts.key(COPY, ChangesMode.STAGING_AREA).getOrThrow()
        assertEquals("", drafts.read(COPY, feature).getOrThrow())
        drafts.write(COPY, feature, "On feature").getOrThrow()
        assertEquals("On main", drafts.read(COPY, main).getOrThrow())
        assertEquals("On feature", drafts.read(COPY, feature).getOrThrow())

        val list = drafts.key(COPY, ChangesMode.CHANGELISTS).getOrThrow()
        assertEquals(DraftKey.Changelist("agent"), list)
        drafts.write(COPY, list, "Agent work").getOrThrow()
        assertEquals("Agent work", books.book.find("agent")?.draft)
        assertEquals(
            "On main",
            drafts.read(COPY, main).getOrThrow(),
            "the branch's draft is kept while a list's is written",
        )
    }

    @Test
    fun `a blank draft forgets the entry, and a detached head has its own key`() = runTest {
        val access = FakeAccess(Head.Detached(com.srctool.graft.data.git.api.models.ObjectId("a".repeat(40))))
        val state = FakeUiState()
        val drafts = FileDraftsUseCase(access, state, FakeChangelists())
        val key = drafts.key(COPY, ChangesMode.STAGING_AREA).getOrThrow()
        assertEquals(DraftKey.Branch("HEAD"), key)
        drafts.write(COPY, key, "x").getOrThrow()
        assertEquals(mapOf("HEAD" to "x"), state.state.changes.drafts)
        drafts.write(COPY, key, "  \n").getOrThrow()
        assertEquals(emptyMap(), state.state.changes.drafts)
    }

    @Test
    fun `a draft for a list that was deleted is not kept`() = runTest {
        val books = FakeChangelists()
        val drafts = FileDraftsUseCase(FakeAccess(), FakeUiState(), books)
        drafts.write(COPY, DraftKey.Changelist("deleted"), "x").getOrThrow()
        assertEquals("", drafts.read(COPY, DraftKey.Changelist("deleted")).getOrThrow())
        assertEquals("", books.book.find("default")?.draft)
    }
}
