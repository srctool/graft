// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.app

import com.srctool.graft.core.configuration.AppFolders
import com.srctool.graft.data.app.api.models.AppSettings
import com.srctool.graft.data.app.api.models.ChangelistBook
import com.srctool.graft.data.app.api.models.ChangelistNameProblem
import com.srctool.graft.data.app.api.models.ChangesMode
import com.srctool.graft.data.app.api.models.ChangesPanelState
import com.srctool.graft.data.app.api.models.ChangesView
import com.srctool.graft.data.app.api.models.CurrentFile
import com.srctool.graft.data.app.api.models.CurrentHunk
import com.srctool.graft.data.app.api.models.FileAssignment
import com.srctool.graft.data.app.api.models.RepositoryUiState
import com.srctool.graft.data.app.api.models.SavedStatus
import com.srctool.graft.data.app.api.models.StoredHunk
import com.srctool.graft.data.app.di.AppDataModule
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Changelists: the book's operations, `changelists.json`, and the mode in `state.json`. */
class ChangelistsTest {
    @TempDir
    lateinit var temp: Path

    private val module by lazy {
        val data = temp.resolve("data")
        AppDataModule(AppFolders(data, data, temp.resolve("cache"), temp.resolve("logs")))
    }

    private fun hunk(start: Int, tag: String = "x") =
        StoredHunk(start, 7, "change-$tag", "removed-$tag", "base-$tag", 1)

    private fun gitDir(name: String = "repo"): Path = Files.createDirectories(temp.resolve(name).resolve(".git"))

    // --- the book ---------------------------------------------------------------------------------------------

    @Test
    fun `a new book has a Default list that is active`() {
        val book = ChangelistBook()
        assertEquals(listOf("Default"), book.lists.map { it.name })
        assertEquals(ChangelistBook.DEFAULT_ID, book.activeId)
    }

    @Test
    fun `lists are created, renamed, described and given drafts`() {
        val book =
            ChangelistBook()
                .create("a", "Login fix", "the bug")
                .rename("a", "Login")
                .describe("a", "the real bug")
                .withDraft("a", "Fix login\n\nBody")
        val list = book.find("a")!!
        assertEquals(
            Triple("Login", "the real bug", "Fix login\n\nBody"),
            Triple(list.name, list.description, list.draft),
        )
    }

    @Test
    fun `names are unique ignoring case, and never blank`() {
        val book = ChangelistBook().create("a", "Debug")
        assertEquals(ChangelistNameProblem.DUPLICATE, book.nameProblem("debug"))
        assertEquals(ChangelistNameProblem.DUPLICATE, book.nameProblem("DEFAULT"))
        assertEquals(ChangelistNameProblem.EMPTY, book.nameProblem("  "))
        assertNull(book.nameProblem("Debug", except = "a"))
        assertFailsWith<IllegalArgumentException> { book.create("b", "debug") }
        assertFailsWith<IllegalArgumentException> { book.rename(ChangelistBook.DEFAULT_ID, "Other") }
    }

    @Test
    fun `deleting a list moves its changes to Default and the Default list stays`() {
        val book =
            ChangelistBook()
                .create("a", "A")
                .activate("a")
                .withFiles(listOf(CurrentFile("new.txt")))
                .withHunks(listOf(CurrentHunk("x.txt", hunk(1), null)))
                .delete("a")
        assertEquals(ChangelistBook.DEFAULT_ID, book.activeId)
        assertEquals(listOf(FileAssignment("new.txt", ChangelistBook.DEFAULT_ID)), book.files)
        assertEquals(ChangelistBook.DEFAULT_ID, book.hunks.single().listId)
        assertFailsWith<IllegalArgumentException> { book.delete(ChangelistBook.DEFAULT_ID) }
    }

    @Test
    fun `new changes land in the active list and known ones stay where they are`() {
        val first = ChangelistBook().create(
            "a",
            "A",
        ).activate("a").withHunks(listOf(CurrentHunk("x.txt", hunk(1), null)))
        assertEquals("a", first.hunks.single().listId)
        val second =
            first.activate(ChangelistBook.DEFAULT_ID).withHunks(
                listOf(CurrentHunk("x.txt", hunk(1), "a"), CurrentHunk("x.txt", hunk(30, "y"), null)),
            )
        assertEquals(listOf("a", ChangelistBook.DEFAULT_ID), second.hunks.map { it.listId })
    }

    @Test
    fun `a hunk whose owner no longer exists falls to the active list`() {
        val book = ChangelistBook().withHunks(listOf(CurrentHunk("x.txt", hunk(1), "gone")))
        assertEquals(ChangelistBook.DEFAULT_ID, book.hunks.single().listId)
    }

    @Test
    fun `paths keep their list, follow a rename, and are forgotten when no longer changed`() {
        val book =
            ChangelistBook().create("a", "A").withFiles(listOf(CurrentFile("old.txt"), CurrentFile("keep.txt")))
                .moveFile("old.txt", "a")
        val next = book.withFiles(listOf(CurrentFile("renamed.txt", originalPath = "old.txt"), CurrentFile("new.txt")))
        assertEquals(
            listOf(FileAssignment("renamed.txt", "a"), FileAssignment("new.txt", ChangelistBook.DEFAULT_ID)),
            next.files,
        )
    }

    @Test
    fun `moving a file moves its hunks and moving a hunk moves only it`() {
        val book =
            ChangelistBook().create("a", "A")
                .withHunks(listOf(CurrentHunk("x.txt", hunk(1), null), CurrentHunk("x.txt", hunk(30, "y"), null)))
        val one = book.moveHunk("x.txt", hunk(30, "y"), "a")
        assertEquals(listOf(ChangelistBook.DEFAULT_ID, "a"), one.hunks.map { it.listId })
        assertEquals(listOf("a", "a"), one.moveFile("x.txt", "a").hunks.map { it.listId })
    }

    @Test
    fun `the switch from the staging area makes a Staged list and leaves the rest alone`() {
        val book =
            ChangelistBook().withHunks(
                listOf(CurrentHunk("x.txt", hunk(1), null), CurrentHunk("x.txt", hunk(30, "y"), null)),
            )
                .withFiles(listOf(CurrentFile("new.txt"), CurrentFile("other.txt")))
        val staged =
            book.withStaged("s", "Staged", listOf(CurrentHunk("x.txt", hunk(30, "y"), null)), listOf("new.txt"))
        assertEquals(listOf("Default", "Staged"), staged.lists.map { it.name })
        assertEquals(listOf(ChangelistBook.DEFAULT_ID, "s"), staged.hunks.map { it.listId })
        assertEquals(
            mapOf("other.txt" to ChangelistBook.DEFAULT_ID, "new.txt" to "s"),
            staged.files.associate {
                it.path to
                    it.listId
            },
        )
        // Switching again later reuses the list, and nothing staged changes nothing.
        assertEquals(staged, staged.withStaged("t", "Staged", emptyList(), emptyList()))
        val again = staged.withStaged("t", "Staged", emptyList(), listOf("other.txt"))
        assertEquals(listOf("Default", "Staged"), again.lists.map { it.name })
        assertEquals("s", again.files.first { it.path == "other.txt" }.listId)
    }

    @Test
    fun `ownedBy lists what one list holds`() {
        val book =
            ChangelistBook().create("a", "A")
                .withHunks(listOf(CurrentHunk("x.txt", hunk(1), "a"), CurrentHunk("y.txt", hunk(1), null)))
                .withFiles(listOf(CurrentFile("n.txt")))
                .moveFile("n.txt", "a")
        val owned = book.ownedBy("a")
        assertEquals(setOf("n.txt"), owned.files)
        assertEquals(listOf("x.txt"), owned.hunks.map { it.path })
    }

    // --- the file ---------------------------------------------------------------------------------------------

    @Test
    fun `changelists are saved per worktree and read back`() = runBlocking<Unit> {
        val main = gitDir("main")
        val linked = gitDir("linked")
        val repository = module.changelists
        assertEquals(SavedStatus.Missing, repository.read(main).getOrThrow().status)
        repository.update(main) {
            it.create("a", "Agent", "kept apart").withDraft("a", "WIP").activate("a")
                .withHunks(listOf(CurrentHunk("x.txt", hunk(4), null)))
                .withFiles(listOf(CurrentFile("n.txt")))
        }.getOrThrow()
        val read = repository.read(main).getOrThrow()
        assertEquals(SavedStatus.Current, read.status)
        assertEquals("a", read.value.activeId)
        assertEquals("WIP", read.value.find("a")!!.draft)
        assertEquals(listOf(hunk(4)), read.value.hunks.map { it.hunk })
        // Another worktree has lists of its own.
        assertEquals(ChangelistBook(), repository.read(linked).getOrThrow().value)
        assertTrue(Files.exists(main.resolve("graft/changelists.json")))
        // A new process reads the same file.
        val data = temp.resolve("data")
        val fresh = AppDataModule(AppFolders(data, data, temp.resolve("cache"), temp.resolve("logs")))
        assertEquals(read.value, fresh.changelists.read(main).getOrThrow().value)
        assertTrue(
            Files.readString(main.resolve("graft/changelists.json")).trimStart().startsWith("{\n    \"version\": 1"),
        )
    }

    @Test
    fun `a file without a Default list or with a missing active list still reads`() = runBlocking<Unit> {
        val dir = gitDir()
        Files.createDirectories(dir.resolve("graft"))
        Files.writeString(
            dir.resolve("graft/changelists.json"),
            """{"version":1,"lists":[{"id":"a","name":"A"}],"active":"zzz","files":[{"path":"n.txt","list":"nope"}]}""",
        )
        val book = module.changelists.read(dir).getOrThrow().value
        assertEquals(listOf("Default", "A"), book.lists.map { it.name })
        assertEquals(ChangelistBook.DEFAULT_ID, book.activeId)
        assertEquals(ChangelistBook.DEFAULT_ID, book.files.single().listId)
    }

    @Test
    fun `a file from a newer Graft is read and never rewritten`() = runBlocking<Unit> {
        val dir = gitDir()
        Files.createDirectories(dir.resolve("graft"))
        val text = """{"version":2,"lists":[{"id":"default","name":"Default","color":"red"}],"future":true}"""
        Files.writeString(dir.resolve("graft/changelists.json"), text)
        val read = module.changelists.read(dir).getOrThrow()
        assertEquals(SavedStatus.Newer(2), read.status)
        assertIs<Result.Failure>(module.changelists.update(dir) { it.create("a", "A") })
        assertEquals(text, Files.readString(dir.resolve("graft/changelists.json")))
    }

    @Test
    fun `a corrupt file is set aside and the defaults are used`() = runBlocking<Unit> {
        val dir = gitDir()
        Files.createDirectories(dir.resolve("graft"))
        Files.writeString(dir.resolve("graft/changelists.json"), "{not json")
        val read = module.changelists.read(dir).getOrThrow()
        assertIs<SavedStatus.Recovered>(read.status)
        assertEquals(ChangelistBook(), read.value)
    }

    // --- the mode ---------------------------------------------------------------------------------------------

    @Test
    fun `the most specific level wins`() {
        val app = ChangesMode.STAGING_AREA
        assertEquals(ChangesMode.STAGING_AREA, ChangesView.effective(ChangesView.INHERIT, ChangesView.INHERIT, app))
        assertEquals(ChangesMode.CHANGELISTS, ChangesView.effective(ChangesView.INHERIT, ChangesView.CHANGELISTS, app))
        assertEquals(
            ChangesMode.STAGING_AREA,
            ChangesView.effective(ChangesView.STAGING_AREA, ChangesView.CHANGELISTS, ChangesMode.CHANGELISTS),
        )
        assertEquals(ChangesMode.CHANGELISTS, ChangesView.effective(ChangesView.CHANGELISTS, ChangesView.INHERIT, app))
    }

    @Test
    fun `the repository's choice is shared by its worktrees and switching keeps the lists`() = runBlocking<Unit> {
        val common = gitDir("common")
        val worktree = gitDir("worktree")
        assertEquals(ChangesView.INHERIT, module.repositoryUiState.read(common).getOrThrow().value.changesView)
        module.changelists.update(worktree) { it.create("a", "Agent").withDraft("a", "WIP") }.getOrThrow()
        // Into changelists, back to the staging area, and into changelists again.
        module.repositoryUiState.update(common) { it.copy(changesView = ChangesView.CHANGELISTS) }.getOrThrow()
        module.repositoryUiState.update(common) { it.copy(changesView = ChangesView.STAGING_AREA) }.getOrThrow()
        assertEquals(ChangesView.STAGING_AREA, module.repositoryUiState.read(common).getOrThrow().value.changesView)
        module.repositoryUiState.update(common) { it.copy(changesView = ChangesView.CHANGELISTS) }.getOrThrow()
        assertEquals(ChangesView.CHANGELISTS, module.repositoryUiState.read(common).getOrThrow().value.changesView)
        // Names and drafts survived, and the state file kept its other fields.
        assertEquals("WIP", module.changelists.read(worktree).getOrThrow().value.find("a")!!.draft)
        assertEquals(
            RepositoryUiState().copy(changesView = ChangesView.CHANGELISTS),
            module.repositoryUiState.read(common).getOrThrow().value,
        )
    }

    @Test
    fun `an unknown mode from a newer Graft reads as inherit, and the app default is staging`() = runBlocking<Unit> {
        val common = gitDir("common")
        Files.createDirectories(common.resolve("graft"))
        Files.writeString(common.resolve("graft/state.json"), """{"version":1,"changesView":"quantum"}""")
        assertEquals(ChangesView.INHERIT, module.repositoryUiState.read(common).getOrThrow().value.changesView)
        assertEquals(ChangesMode.STAGING_AREA, AppSettings().changesView)
    }

    @Test
    fun `the Changes panel's choices and per-branch drafts are kept in state json, sorted`() = runBlocking<Unit> {
        val common = gitDir("common")
        assertEquals(ChangesPanelState(), module.repositoryUiState.read(common).getOrThrow().value.changes)
        val panel =
            ChangesPanelState(
                tree = false,
                collapsed = setOf("src/b", "src/a"),
                drafts = mapOf("refs/heads/main" to "Fix login\n\nBody", "refs/heads/feat" to "WIP"),
            )
        module.repositoryUiState.update(common) { it.copy(changes = panel) }.getOrThrow()
        assertEquals(panel, module.repositoryUiState.read(common).getOrThrow().value.changes)
        val text = Files.readString(common.resolve("graft/state.json"))
        assertTrue(text.indexOf("refs/heads/feat") < text.indexOf("refs/heads/main"), text)
        assertTrue(text.indexOf("src/a") < text.indexOf("src/b"), text)
    }

    @Test
    fun `a state file from before the panel state reads as the defaults`() = runBlocking<Unit> {
        val common = gitDir("common")
        Files.createDirectories(common.resolve("graft"))
        Files.writeString(common.resolve("graft/state.json"), """{"version":1,"pinnedRefs":["refs/heads/main"]}""")
        assertEquals(ChangesPanelState(), module.repositoryUiState.read(common).getOrThrow().value.changes)
    }
}
