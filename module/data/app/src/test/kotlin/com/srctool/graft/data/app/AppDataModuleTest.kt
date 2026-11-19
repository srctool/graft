// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.app

import com.srctool.graft.core.configuration.AppFolders
import com.srctool.graft.data.app.api.models.AppSettings
import com.srctool.graft.data.app.api.models.DiffStyle
import com.srctool.graft.data.app.api.models.GraphAuthorKind
import com.srctool.graft.data.app.api.models.GraphBranches
import com.srctool.graft.data.app.api.models.GraphViewState
import com.srctool.graft.data.app.api.models.PanelLayout
import com.srctool.graft.data.app.api.models.RepositoryUiState
import com.srctool.graft.data.app.api.models.SavedStatus
import com.srctool.graft.data.app.api.models.SavedWindow
import com.srctool.graft.data.app.api.models.ThemeChoice
import com.srctool.graft.data.app.di.AppDataModule
import com.srctool.sinew.exception.NotFoundException
import com.srctool.sinew.exception.ValidationException
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Graft's own files, each in a temp folder standing in for the app data folder. */
class AppDataModuleTest {
    @TempDir
    lateinit var temp: Path

    private var now = 1_767_225_600L

    private val folders by lazy {
        val data = temp.resolve("data")
        AppFolders(data, data, temp.resolve("cache"), temp.resolve("logs"))
    }

    private fun module() = AppDataModule(folders) { Instant.ofEpochSecond(now++) }

    /** A folder that looks like a repository to the file system (git isn't needed for these checks). */
    private fun repo(name: String): Path =
        Files.createDirectories(temp.resolve(name).resolve(".git")).parent.toRealPath()

    @Test
    fun `settings start at their defaults and round-trip`() = runBlocking<Unit> {
        val settings = module().settings
        val first = settings.read().getOrThrow()
        assertEquals(AppSettings(), first.value)
        assertEquals(SavedStatus.Missing, first.status)

        settings.update { it.copy(theme = ThemeChoice.DARK, diffStyle = DiffStyle.SIDE_BY_SIDE) }.getOrThrow()
        val text = Files.readString(folders.data.resolve("settings.json"))
        assertTrue(text.trimStart().startsWith("{") && text.indexOf("\"version\"") < text.indexOf("\"theme\""), text)
        assertTrue("\"side-by-side\"" in text && "\"dark\"" in text, text)

        val again = module().settings.read().getOrThrow()
        assertEquals(SavedStatus.Current, again.status)
        assertEquals(ThemeChoice.DARK, again.value.theme)
        assertEquals(DiffStyle.SIDE_BY_SIDE, again.value.diffStyle)
    }

    @Test
    fun `settings from a newer Graft are read but never rewritten`() = runBlocking<Unit> {
        val file = Files.createDirectories(folders.data).resolve("settings.json")
        val newer = """{"version": 2, "theme": "light", "futureThing": true}"""
        Files.writeString(file, newer)
        val settings = module().settings
        val saved = settings.read().getOrThrow()
        assertEquals(SavedStatus.Newer(2), saved.status)
        assertTrue(saved.readOnly)
        assertEquals(ThemeChoice.LIGHT, saved.value.theme)
        assertIs<Result.Failure>(settings.update { it.copy(theme = ThemeChoice.DARK) })
        assertEquals(newer, Files.readString(file))
    }

    @Test
    fun `a file from before the welcome steps reads as not onboarded, and the flag is kept once set`() =
        runBlocking<Unit> {
            val file = Files.createDirectories(folders.data).resolve("settings.json")
            Files.writeString(file, """{"version":1,"theme":"dark"}""")
            val settings = module().settings
            assertEquals(false, settings.read().getOrThrow().value.onboarded)
            settings.update { it.copy(onboarded = true) }.getOrThrow()
            val again = module().settings.read().getOrThrow().value
            assertEquals(true, again.onboarded)
            assertEquals(ThemeChoice.DARK, again.theme)
        }

    @Test
    fun `every read and update is published to changes, so the window can follow`() = runBlocking<Unit> {
        val settings = module().settings
        assertEquals(null, settings.changes.value)
        settings.read().getOrThrow()
        assertEquals(AppSettings(), settings.changes.value)
        settings.update { it.copy(theme = ThemeChoice.DARK) }.getOrThrow()
        assertEquals(ThemeChoice.DARK, settings.changes.value?.theme)
    }

    @Test
    fun `corrupt settings are set aside and the defaults used`() = runBlocking<Unit> {
        val file = Files.createDirectories(folders.data).resolve("settings.json")
        Files.writeString(file, "{ not json")
        val saved = module().settings.read().getOrThrow()
        val status = assertIs<SavedStatus.Recovered>(saved.status)
        assertEquals(AppSettings(), saved.value)
        assertEquals("{ not json", Files.readString(status.corruptCopy))
    }

    @Test
    fun `recent repositories, newest first, pinned on top`() = runBlocking<Unit> {
        val known = module().knownRepositories
        val (a, b, c) = listOf(repo("a"), repo("b"), repo("c"))
        known.opened(a).getOrThrow()
        known.opened(b).getOrThrow()
        known.opened(c).getOrThrow()
        known.setPinned(a, true).getOrThrow()
        assertEquals(listOf(a, c, b), known.list().getOrThrow().value.map { it.path })
        assertEquals(listOf(true, false, false), known.list().getOrThrow().value.map { it.pinned })

        known.opened(b).getOrThrow()
        assertEquals(listOf(a, b, c), known.list().getOrThrow().value.map { it.path })
        known.remove(c).getOrThrow()
        assertEquals(listOf(a, b), module().knownRepositories.list().getOrThrow().value.map { it.path })
    }

    @Test
    fun `one entry per real path, through a symlink too`() = runBlocking<Unit> {
        val known = module().knownRepositories
        val target = repo("real")
        val link = temp.resolve("link")
        try {
            Files.createSymbolicLink(link, target)
        } catch (e: IOException) {
            assumeTrue(false, "symlinks aren't allowed here: ${e.message}")
        } catch (e: UnsupportedOperationException) {
            assumeTrue(false, "symlinks aren't supported here: ${e.message}")
        }
        known.opened(target).getOrThrow()
        known.opened(link).getOrThrow()
        known.opened(target.resolve("..").resolve("real")).getOrThrow()
        assertEquals(listOf(target), known.list().getOrThrow().value.map { it.path })
    }

    @Test
    fun `a moved repository shows as missing until it's located`() = runBlocking<Unit> {
        val known = module().knownRepositories
        val old = repo("old")
        known.opened(old).getOrThrow()
        known.setPinned(old, true).getOrThrow()
        val moved = Files.move(old, temp.resolve("moved")).toRealPath()
        val listed = known.list().getOrThrow().value.single()
        assertEquals(old, listed.path)
        assertTrue(listed.missing)

        known.relocate(old, moved).getOrThrow()
        val located = known.list().getOrThrow().value.single()
        assertEquals(moved, located.path)
        assertTrue(located.pinned)
        assertFalse(located.missing)
    }

    @Test
    fun `a folder whose repository was deleted is missing too`() = runBlocking<Unit> {
        val known = module().knownRepositories
        val repo = repo("gone")
        known.opened(repo).getOrThrow()
        Files.delete(repo.resolve(".git"))
        assertTrue(known.list().getOrThrow().value.single().missing)
    }

    @Test
    fun `the oldest unpinned repositories drop off, pinned ones stay`() = runBlocking<Unit> {
        val known = module().knownRepositories
        val pinned = repo("pinned")
        known.opened(pinned).getOrThrow()
        known.setPinned(pinned, true).getOrThrow()
        val others = (1..32).map { repo("r$it") }
        others.forEach { known.opened(it).getOrThrow() }
        val listed = known.list().getOrThrow().value.map { it.path }
        assertEquals(31, listed.size)
        assertEquals(pinned, listed.first())
        assertFalse(others[0] in listed || others[1] in listed)
        assertTrue(others.last() in listed)
    }

    @Test
    fun `windows and repositories share the app state file`() = runBlocking<Unit> {
        val data = module()
        val windows =
            listOf(
                SavedWindow(10, 20, 1200, 800, false, tabs = listOf("a", "b"), activeTab = 1),
                SavedWindow(-1600, 0, 800, 600, true),
            )
        data.windows.save(windows).getOrThrow()
        data.knownRepositories.opened(repo("a")).getOrThrow()
        val again = module()
        assertEquals(windows, again.windows.windows().getOrThrow())
        assertEquals(1, again.knownRepositories.list().getOrThrow().value.size)
        assertTrue(Files.exists(folders.data.resolve("app-state.json")))
    }

    @Test
    fun `a window saved before tabs were kept reads as Home in front`() = runBlocking<Unit> {
        val file = folders.data.resolve("app-state.json")
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            """{"version":1,"windows":[{"x":1,"y":2,"width":900,"height":700,"maximized":true}]}""",
        )
        assertEquals(listOf(SavedWindow(1, 2, 900, 700, true)), module().windows.windows().getOrThrow())
    }

    @Test
    fun `workspaces are kept in the default profile`() = runBlocking<Unit> {
        val workspaces = module().workspaces
        val (a, b) = repo("a") to repo("b")
        val created = workspaces.create("  Work  ", listOf(a, a, b)).getOrThrow()
        assertEquals("Work", created.name)
        assertEquals(listOf(a, b), created.repositories)
        assertTrue(Files.exists(folders.profile("default").resolve("workspace.json")))

        val c = repo("c")
        workspaces.add(created.id, listOf(b, c)).getOrThrow()
        workspaces.rename(created.id, "Day job").getOrThrow()
        workspaces.removeRepository(created.id, a).getOrThrow()
        val listed = module().workspaces.list().getOrThrow().value.single()
        assertEquals("Day job", listed.name)
        assertEquals(listOf(b, c), listed.repositories)

        workspaces.delete(created.id).getOrThrow()
        assertEquals(emptyList(), workspaces.list().getOrThrow().value)
    }

    @Test
    fun `a nameless or unknown workspace is refused`() = runBlocking<Unit> {
        val workspaces = module().workspaces
        assertIs<ValidationException>(assertIs<Result.Failure>(workspaces.create(" ")).error)
        assertIs<NotFoundException>(assertIs<Result.Failure>(workspaces.rename("nope", "x")).error)
        assertIs<NotFoundException>(assertIs<Result.Failure>(workspaces.delete("nope")).error)
    }

    @Test
    fun `per-repository UI state lives in the common git folder`() = runBlocking<Unit> {
        val commonDir = repo("r").resolve(".git")
        val state = module().repositoryUiState
        assertEquals(RepositoryUiState(), state.read(commonDir).getOrThrow().value)
        state.update(commonDir) { it.copy(hiddenRefs = setOf("refs/heads/z", "refs/heads/a")) }.getOrThrow()
        state.update(commonDir) { it.copy(pinnedRefs = it.pinnedRefs + "refs/heads/main") }.getOrThrow()

        val file = commonDir.resolve("graft").resolve("state.json")
        val text = Files.readString(file)
        assertTrue(text.indexOf("refs/heads/a") < text.indexOf("refs/heads/z"), text)
        val read = module().repositoryUiState.read(commonDir).getOrThrow()
        assertEquals(SavedStatus.Current, read.status)
        assertEquals(
            RepositoryUiState(
                hiddenRefs = setOf("refs/heads/a", "refs/heads/z"),
                pinnedRefs = setOf("refs/heads/main"),
            ),
            read.value,
        )
    }

    @Test
    fun `deleting the graft folder resets the UI state`() = runBlocking<Unit> {
        val commonDir = repo("r").resolve(".git")
        val state = module().repositoryUiState
        state.update(commonDir) { it.copy(soloRefs = setOf("refs/heads/main")) }.getOrThrow()
        commonDir.resolve("graft").toFile().deleteRecursively()
        assertEquals(RepositoryUiState(), state.read(commonDir).getOrThrow().value)
    }

    @Test
    fun `a state file from before ADR-27 reads with the graph's defaults`() = runBlocking<Unit> {
        val commonDir = repo("r").resolve(".git")
        val fixture = Paths.get(System.getProperty("graft.fixtures")).resolve("app/state-v1-before-adr27.json")
        Files.createDirectories(commonDir.resolve("graft"))
        Files.copy(fixture, commonDir.resolve("graft").resolve("state.json"))
        val read = module().repositoryUiState.read(commonDir).getOrThrow()
        assertEquals(SavedStatus.Current, read.status)
        assertEquals(
            RepositoryUiState(hiddenRefs = setOf("refs/heads/old"), pinnedRefs = setOf("refs/heads/main")),
            read.value,
        )
        assertEquals(GraphViewState(), read.value.graph)
    }

    @Test
    fun `the graph's choices and columns round-trip in ADR-27's names`() = runBlocking<Unit> {
        val commonDir = repo("r").resolve(".git")
        val state = module().repositoryUiState
        val graph =
            GraphViewState(
                branches = GraphBranches.CURRENT,
                hideRemotes = true,
                firstParent = true,
                authorKinds = setOf(GraphAuthorKind.PERSON, GraphAuthorKind.AI_ASSISTED),
                commitGraphOffered = true,
            )
        val columns = mapOf("author" to 160f, "sha" to null)
        state.update(commonDir) { it.copy(graph = graph, columns = columns) }.getOrThrow()

        val text = Files.readString(commonDir.resolve("graft").resolve("state.json"))
        assertTrue("\"branches\": \"current\"" in text, text)
        assertTrue("\"aiAssisted\"" in text && "\"bot\"" !in text, text)
        assertTrue("\"sha\": null" in text, text)
        val read = module().repositoryUiState.read(commonDir).getOrThrow().value
        assertEquals(graph, read.graph)
        assertEquals(columns, read.columns)
    }

    @Test
    fun `the panel layout round-trips and an older file has none`() = runBlocking<Unit> {
        val commonDir = repo("r").resolve(".git")
        val state = module().repositoryUiState
        assertEquals(PanelLayout(), state.read(commonDir).getOrThrow().value.panels)
        val panels =
            PanelLayout(
                sides = mapOf("operations" to "right"),
                open = mapOf("left" to "branches", "bottom" to "gitlog"),
                sizes = mapOf("left" to 300f, "bottom" to 180f),
                saved = true,
            )
        state.update(commonDir) { it.copy(panels = panels) }.getOrThrow()
        assertEquals(panels, module().repositoryUiState.read(commonDir).getOrThrow().value.panels)
    }

    @Test
    fun `values a newer Graft wrote read as the defaults`() = runBlocking<Unit> {
        val commonDir = repo("r").resolve(".git")
        Files.createDirectories(commonDir.resolve("graft"))
        Files.writeString(
            commonDir.resolve("graft").resolve("state.json"),
            """{"version": 1, "graph": {"branches": "tracked", "authorTypes": ["robot"], "zoom": 2}}""",
        )
        val graph = module().repositoryUiState.read(commonDir).getOrThrow().value.graph
        assertEquals(GraphBranches.ALL, graph.branches)
        assertEquals(GraphAuthorKind.entries.toSet(), graph.authorKinds)
    }
}
