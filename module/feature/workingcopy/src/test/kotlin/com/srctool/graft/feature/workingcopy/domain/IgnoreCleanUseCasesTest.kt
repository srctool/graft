// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain

import com.srctool.graft.data.git.api.models.CleanEntry
import com.srctool.graft.data.git.api.models.CleanPreview
import com.srctool.graft.data.git.api.models.CleanResult
import com.srctool.graft.data.git.api.models.CleanScope
import com.srctool.graft.data.git.api.models.IgnoreExplanation
import com.srctool.graft.data.git.api.models.IgnorePreview
import com.srctool.graft.data.git.api.models.IgnoreTarget
import com.srctool.graft.data.git.api.models.IgnoreTemplate
import com.srctool.graft.data.git.api.models.IgnoreWrite
import com.srctool.graft.data.git.api.repositories.CleanRepository
import com.srctool.graft.data.git.api.repositories.IgnoreRepository
import com.srctool.graft.feature.workingcopy.domain.api.usecases.CleanTicks
import com.srctool.graft.feature.workingcopy.domain.api.usecases.IgnoreChoice
import com.srctool.graft.feature.workingcopy.domain.implementation.usecases.GitCleanUseCase
import com.srctool.graft.feature.workingcopy.domain.implementation.usecases.GitIgnoreUseCase
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The ignore plan, the template merge, and the ticks of the clean dialog. */
class IgnoreCleanUseCasesTest {
    private class FakeIgnore(var tracked: List<String> = emptyList()) : IgnoreRepository {
        val previews = ArrayList<Pair<List<String>, String>>()
        val added = ArrayList<Pair<IgnoreTarget, List<String>>>()
        val untracked = ArrayList<List<String>>()
        val calls = ArrayList<String>()
        var explanation: List<IgnoreExplanation> = emptyList()
        val templatesList = listOf(IgnoreTemplate("Kotlin", listOf("*.class", "build/")))

        override suspend fun file(repository: Path, target: IgnoreTarget): Result<Path> =
            Result.Success(Paths.get("/work/repo/.gitignore"))

        override suspend fun preview(repository: Path, patterns: List<String>, base: String): Result<IgnorePreview> {
            previews += patterns to base
            return Result.Success(IgnorePreview(listOf("a.log"), tracked, emptyList(), false))
        }

        override suspend fun add(repository: Path, target: IgnoreTarget, patterns: List<String>): Result<IgnoreWrite> {
            calls += "add"
            added += target to patterns
            return Result.Success(IgnoreWrite(Paths.get("/work/repo/.gitignore"), patterns, emptyList(), false))
        }

        override suspend fun explain(repository: Path, paths: List<String>): Result<List<IgnoreExplanation>> =
            Result.Success(explanation)

        override suspend fun untrack(repository: Path, paths: List<String>): Result<Unit> {
            calls += "untrack"
            untracked += paths
            return Result.Success(Unit)
        }

        override fun templates(): List<IgnoreTemplate> = templatesList

        override fun templatesLicense(): String = "CC0"
    }

    private fun entry(path: String, bytes: Long, lost: Long = 0, nested: Boolean = false) =
        CleanEntry(path, path.endsWith("/"), bytes, lost, nested)

    // ---- ignore

    @Test
    fun `the menu's choices become patterns, previewed before they are written`() = runTest {
        val fake = FakeIgnore()
        val use = GitIgnoreUseCase(fake)
        val root = IgnoreTarget.Gitignore()
        assertEquals(
            listOf("/logs/app.log"),
            use.plan(COPY, "logs/app.log", IgnoreChoice.THIS_FILE, root).getOrThrow().patterns,
        )
        assertEquals(
            listOf("*.log"),
            use.plan(COPY, "logs/app.log", IgnoreChoice.EXTENSION, root).getOrThrow().patterns,
        )
        assertEquals(
            listOf("/logs/"),
            use.plan(COPY, "logs/app.log", IgnoreChoice.THIS_FOLDER, root).getOrThrow().patterns,
        )
        assertEquals(
            listOf("/build/"),
            use.plan(COPY, "build", IgnoreChoice.THIS_FOLDER, root, folder = true).getOrThrow().patterns,
        )
        assertEquals(
            listOf("tmp/**/*.bak"),
            use.plan(COPY, "x", IgnoreChoice.CUSTOM, root, custom = "  tmp/**/*.bak ").getOrThrow().patterns,
        )
        assertEquals(5, fake.previews.size)
    }

    @Test
    fun `nothing to offer is an empty plan, not an error`() = runTest {
        val use = GitIgnoreUseCase(FakeIgnore())
        val root = IgnoreTarget.Gitignore()
        assertEquals(emptyList(), use.plan(COPY, "Makefile", IgnoreChoice.EXTENSION, root).getOrThrow().patterns)
        assertEquals(emptyList(), use.plan(COPY, "top.txt", IgnoreChoice.THIS_FOLDER, root).getOrThrow().patterns)
        assertEquals(emptyList(), use.plan(COPY, "x", IgnoreChoice.CUSTOM, root, custom = "   ").getOrThrow().patterns)
    }

    @Test
    fun `a gitignore in a folder gets patterns relative to it, and its preview says where they apply`() = runTest {
        val fake = FakeIgnore()
        val plan =
            GitIgnoreUseCase(fake)
                .plan(COPY, "logs/deep/app.log", IgnoreChoice.THIS_FILE, IgnoreTarget.Gitignore("logs"))
                .getOrThrow()
        assertEquals(listOf("/deep/app.log"), plan.patterns)
        assertEquals(listOf(listOf("/deep/app.log") to "logs"), fake.previews)
    }

    @Test
    fun `a path outside the gitignore's folder is a mistake of the caller`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            GitIgnoreUseCase(
                FakeIgnore(),
            ).plan(COPY, "other/a.log", IgnoreChoice.THIS_FILE, IgnoreTarget.Gitignore("logs"))
        }
    }

    @Test
    fun `the plan shows which matches are already tracked`() = runTest {
        val plan =
            GitIgnoreUseCase(FakeIgnore(tracked = listOf("secret.env")))
                .plan(COPY, "secret.env", IgnoreChoice.THIS_FILE, IgnoreTarget.InfoExclude)
                .getOrThrow()
        assertEquals(listOf("secret.env"), plan.alreadyTracked)
    }

    @Test
    fun `applying writes the patterns first, then untracks when asked`() = runTest {
        val fake = FakeIgnore(tracked = listOf("secret.env"))
        val use = GitIgnoreUseCase(fake)
        val plan = use.plan(COPY, "secret.env", IgnoreChoice.THIS_FILE, IgnoreTarget.GlobalExcludes).getOrThrow()
        use.apply(COPY, plan, untrack = true).getOrThrow()
        assertEquals(listOf("add", "untrack"), fake.calls)
        assertEquals(listOf(IgnoreTarget.GlobalExcludes to listOf("/secret.env")), fake.added.toList())
        assertEquals(listOf(listOf("secret.env")), fake.untracked.toList())
    }

    @Test
    fun `nothing is untracked unless asked, or when nothing tracked matches`() = runTest {
        val fake = FakeIgnore(tracked = listOf("secret.env"))
        val use = GitIgnoreUseCase(fake)
        val plan = use.plan(COPY, "secret.env", IgnoreChoice.THIS_FILE, IgnoreTarget.InfoExclude).getOrThrow()
        use.apply(COPY, plan, untrack = false).getOrThrow()
        val none = GitIgnoreUseCase(
            FakeIgnore(),
        ).plan(COPY, "a.log", IgnoreChoice.EXTENSION, IgnoreTarget.InfoExclude).getOrThrow()
        GitIgnoreUseCase(fake).apply(COPY, none, untrack = true).getOrThrow()
        assertTrue(fake.untracked.isEmpty())
    }

    @Test
    fun `why is this ignored gives the first rule or none`() = runTest {
        val fake = FakeIgnore()
        val use = GitIgnoreUseCase(fake)
        assertNull(use.explain(COPY, "a.log").getOrThrow())
        fake.explanation = listOf(IgnoreExplanation("a.log", ".gitignore", 2, "*.log"))
        assertEquals(2, use.explain(COPY, "a.log").getOrThrow()?.line)
    }

    @Test
    fun `a template is added under its own heading`() = runTest {
        val fake = FakeIgnore()
        val use = GitIgnoreUseCase(fake)
        assertEquals(listOf("Kotlin"), use.templates().map { it.name })
        use.addTemplate(COPY, IgnoreTarget.Gitignore(), "Kotlin").getOrThrow()
        assertEquals(listOf("# Kotlin", "*.class", "build/"), fake.added.single().second)
        use.addTemplate(COPY, IgnoreTarget.Gitignore(), "Nope").getOrThrow()
        assertEquals(emptyList(), fake.added.last().second)
    }

    // ---- clean

    private val preview =
        CleanPreview(
            CleanScope.UNTRACKED_AND_IGNORED,
            listOf(
                entry("node_modules/", 9_000, lost = 9_000),
                entry("vendor/", 500, lost = 500, nested = true),
                entry("out.txt", 40),
                entry("notes.txt", 10),
            ),
        )

    @Test
    fun `everything but nested repositories starts ticked`() {
        val ticks = CleanTicks.of(preview)
        assertEquals(listOf("node_modules/", "out.txt", "notes.txt"), ticks.paths)
        assertTrue(!ticks.isTicked("vendor/"))
        assertEquals(9_050, ticks.bytes)
        assertEquals(9_000, ticks.lostBytes)
        assertEquals(emptySet(), ticks.nested)
    }

    @Test
    fun `ticking a nested repository is the user's own act and counts as its confirmation`() {
        val ticks = CleanTicks.of(preview).toggle("vendor/")
        assertEquals(setOf("vendor/"), ticks.nested)
        assertEquals(9_550, ticks.bytes)
        assertEquals(9_500, ticks.lostBytes)
        assertEquals(setOf<String>(), ticks.toggle("vendor/").nested)
    }

    @Test
    fun `ticks toggle, clear, and ignore paths that aren't in the preview`() {
        val ticks = CleanTicks.of(preview).toggle("out.txt").toggle("not-there")
        assertEquals(listOf("node_modules/", "notes.txt"), ticks.paths)
        assertEquals(emptyList(), ticks.clear().paths)
        assertEquals(listOf("node_modules/", "out.txt", "notes.txt"), ticks.clear().tickAll().paths)
        assertEquals(0, ticks.clear().bytes)
    }

    @Test
    fun `clean sends the ticked paths and the confirmed nested ones with the preview's scope`() = runTest {
        val sent = ArrayList<Triple<CleanScope, List<String>, Set<String>>>()
        val repo =
            object : CleanRepository {
                override suspend fun preview(repository: Path, scope: CleanScope): Result<CleanPreview> =
                    Result.Success(preview)

                override suspend fun clean(
                    repository: Path,
                    scope: CleanScope,
                    paths: List<String>,
                    confirmedNested: Set<String>,
                ): Result<CleanResult> {
                    sent += Triple(scope, paths, confirmedNested)
                    return Result.Success(CleanResult(paths, null, 0))
                }
            }
        val use = GitCleanUseCase(repo)
        val shown = use.preview(COPY, CleanScope.UNTRACKED_AND_IGNORED).getOrThrow()
        val result = use.clean(COPY, CleanTicks.of(shown).toggle("vendor/").toggle("out.txt")).getOrThrow()
        assertEquals(listOf("node_modules/", "vendor/", "notes.txt"), sent.single().second)
        assertEquals(sent.single().second, result.removed)
        assertEquals(CleanScope.UNTRACKED_AND_IGNORED, sent.single().first)
        assertEquals(setOf("vendor/"), sent.single().third)
        assertIs<Result.Success<CleanPreview>>(Result.Success(shown))
    }
}
