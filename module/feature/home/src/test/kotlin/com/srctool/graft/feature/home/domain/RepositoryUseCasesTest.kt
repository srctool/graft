// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.domain

import com.srctool.graft.core.exception.GraftCodes
import com.srctool.graft.data.app.api.models.KnownRepository
import com.srctool.graft.data.app.api.models.Saved
import com.srctool.graft.data.app.api.models.SavedStatus
import com.srctool.graft.data.app.api.models.Workspace
import com.srctool.graft.data.app.api.repositories.FolderScanRepository
import com.srctool.graft.data.app.api.repositories.KnownRepositoryRepository
import com.srctool.graft.data.app.api.repositories.WorkspaceRepository
import com.srctool.graft.data.git.api.models.CloneProgress
import com.srctool.graft.data.git.api.models.CloneRequest
import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.data.git.api.models.RepositorySummary
import com.srctool.graft.data.git.api.repositories.RepositoryAccessRepository
import com.srctool.graft.feature.home.domain.api.models.InitFile
import com.srctool.graft.feature.home.domain.api.models.InitOptions
import com.srctool.graft.feature.home.domain.api.models.InitOutcome
import com.srctool.graft.feature.home.domain.api.models.InitStep
import com.srctool.graft.feature.home.domain.api.models.OpenOutcome
import com.srctool.graft.feature.home.domain.api.models.ScannedRepository
import com.srctool.graft.feature.home.domain.implementation.usecases.BundledInitTemplates
import com.srctool.graft.feature.home.domain.implementation.usecases.GitCloneRepositoryUseCase
import com.srctool.graft.feature.home.domain.implementation.usecases.GitInitRepositoryUseCase
import com.srctool.graft.feature.home.domain.implementation.usecases.GitOpenRepositoryUseCase
import com.srctool.graft.feature.home.domain.implementation.usecases.GitRepositorySummaryUseCase
import com.srctool.graft.feature.home.domain.implementation.usecases.GitScanFolderUseCase
import com.srctool.sinew.models.ErrorMessage
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.SinewException
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositoryUseCasesTest {
    @TempDir
    lateinit var temp: Path

    private val access = FakeAccess()
    private val known = FakeKnown()
    private val open by lazy { GitOpenRepositoryUseCase(access, known) }

    /** A folder standing in for a repository: the fake access answers for it, the file system only has to hold it. */
    private fun repo(name: String): Path = Files.createDirectories(temp.resolve(name)).toRealPath()

    @Test
    fun `a subfolder opens its repository's root, and the root is recorded`() = runTest {
        val root = repo("app")
        val src = Files.createDirectories(root.resolve("src/main"))
        access.locations[src] = RepositoryLocation(root, root.resolve(".git"), root.resolve(".git"))
        val opened = assertIs<OpenOutcome.Opened>(open.open(src))
        assertEquals(root, opened.root)
        assertEquals(Paths.get("src", "main"), opened.subfolder)
        assertEquals(listOf(root), known.opened)
    }

    @Test
    fun `the root itself and a bare repository open without a subfolder`() = runTest {
        val root = repo("app")
        access.locations[root] = RepositoryLocation(root, root.resolve(".git"), root.resolve(".git"))
        assertNull(assertIs<OpenOutcome.Opened>(open.open(root)).subfolder)

        val bare = repo("server.git")
        access.locations[bare] = RepositoryLocation(null, bare, bare)
        val opened = assertIs<OpenOutcome.Opened>(open.open(bare))
        assertEquals(bare, opened.root)
        assertNull(opened.subfolder)
        assertEquals(listOf(root, bare), known.opened)
    }

    @Test
    fun `a missing folder and a plain folder are told apart`() = runTest {
        val gone = temp.resolve("gone")
        assertEquals(OpenOutcome.FolderMissing(gone), open.open(gone))
        val plain = repo("plain")
        assertEquals(OpenOutcome.NotARepository(plain), open.open(plain))
        assertEquals(emptyList(), known.opened)
    }

    @Test
    fun `a folder another user owns shows the exact change, and trusting it opens it`() = runTest {
        val root = repo("shared")
        access.untrusted[root] = "/Users/other/shared"
        val untrusted = assertIs<OpenOutcome.Untrusted>(open.open(root))
        assertEquals("/Users/other/shared", untrusted.folder)
        assertEquals(
            listOf("git", "config", "--global", "--add", "safe.directory", "/Users/other/shared"),
            untrusted.command,
        )
        assertEquals(emptyList(), access.trusted, "nothing is written before the user agrees")

        access.locations[root] = RepositoryLocation(root, root.resolve(".git"), root.resolve(".git"))
        assertIs<OpenOutcome.Opened>(open.trustAndOpen(untrusted, root))
        assertEquals(listOf("/Users/other/shared"), access.trusted)
    }

    @Test
    fun `a failed trust and any other error are reported, not opened`() = runTest {
        val root = repo("shared")
        access.untrusted[root] = "/srv/shared"
        val untrusted = assertIs<OpenOutcome.Untrusted>(open.open(root))
        access.trustFails = true
        assertIs<OpenOutcome.Failed>(open.trustAndOpen(untrusted, root))

        val locked = repo("locked")
        access.errors[locked] = FakeGitError(GraftCodes.Types.LOCKED)
        assertEquals(
            GraftCodes.Types.LOCKED,
            (assertIs<OpenOutcome.Failed>(open.open(locked)).error as SinewException).typeCode,
        )
        assertEquals(emptyList(), known.opened)
    }

    @Test
    fun `a scan marks what Home already lists, and can become a workspace`() = runTest {
        val a = repo("a")
        val b = repo("b")
        known.listed.add(a)
        val workspaces = FakeWorkspaces()
        val scan = GitScanFolderUseCase(FakeScanner(listOf(a, b)), known, workspaces)
        assertEquals(listOf(ScannedRepository(a, true), ScannedRepository(b, false)), scan.scan(temp).getOrThrow())
        assertEquals(listOf(a, b), scan.addAsWorkspace("Work", listOf(a, b)).getOrThrow().repositories)
        assertEquals(emptyList(), known.opened, "a scan doesn't push repositories into the recent list")
    }

    @Test
    fun `a clone is recorded once it finishes, and not when it fails`() = runTest {
        val target = temp.resolve("clone")
        val location = RepositoryLocation(target, target.resolve(".git"), target.resolve(".git"))
        access.cloneSteps =
            listOf(CloneProgress.Phase("Receiving objects", 50, 5, 10), CloneProgress.Finished(location))
        val clone = GitCloneRepositoryUseCase(access, known)
        assertEquals(access.cloneSteps, clone.clone(CloneRequest("https://example.invalid/r.git", target)).toList())
        assertEquals(listOf(target), known.opened)

        access.cloneFails = true
        assertFailsWith<FakeGitError> { clone.clone(CloneRequest("https://example.invalid/r.git", target)).toList() }
        assertEquals(listOf(target), known.opened)
    }

    private val bundled = BundledInitTemplates

    @Test
    fun `create writes the chosen files, then makes the first commit from exactly those`() = runTest {
        val folder = temp.resolve("made")
        val options =
            InitOptions(
                folder,
                "main",
                gitignore = "Kotlin",
                license = "MIT",
                holder = "Dwiki",
                readme = true,
                firstCommit = true,
                year = 2026,
            )
        val outcome = GitInitRepositoryUseCase(access, known).create(options)
        assertIs<InitOutcome.Created>(outcome)
        assertEquals(bundled.gitignore("Kotlin"), Files.readString(folder.resolve(".gitignore")))
        val license = Files.readString(folder.resolve("LICENSE"))
        assertTrue(license.startsWith("MIT License\n\nCopyright (c) 2026 Dwiki"), license.take(80))
        assertEquals("# made\n", Files.readString(folder.resolve("README.md")))
        assertEquals(
            listOf(Triple(folder, listOf(".gitignore", "LICENSE", "README.md"), "Initial commit")),
            access.commits,
        )
        assertEquals(listOf(folder), known.opened)
    }

    @Test
    fun `create without files makes no commit, and never overwrites a file that's there`() = runTest {
        val folder = Files.createDirectories(temp.resolve("existing"))
        Files.writeString(folder.resolve("README.md"), "mine\n")
        val none = GitInitRepositoryUseCase(access, known).create(InitOptions(folder, firstCommit = true))
        assertIs<InitOutcome.Created>(none)
        assertTrue(access.commits.isEmpty(), "nothing was written, so there's nothing to commit")

        val partial = GitInitRepositoryUseCase(
            access,
            known,
        ).create(InitOptions(folder, readme = true, firstCommit = true))
        val step = assertIs<InitOutcome.Partial>(partial)
        assertEquals(InitStep.Writing(InitFile.README), step.step)
        assertEquals("mine\n", Files.readString(folder.resolve("README.md")))
        assertTrue(access.commits.isEmpty())
    }

    @Test
    fun `a failed commit leaves the repository and its files, and a failed init is a failure`() = runTest {
        access.commitFails = true
        val folder = temp.resolve("commit-fails")
        val partial = GitInitRepositoryUseCase(
            access,
            known,
        ).create(InitOptions(folder, readme = true, firstCommit = true))
        assertEquals(InitStep.Committing, assertIs<InitOutcome.Partial>(partial).step)
        assertTrue(Files.exists(folder.resolve("README.md")))
        assertEquals(listOf(folder), known.opened, "the repository exists, so it's listed")

        access.initFails = true
        val failed = GitInitRepositoryUseCase(access, known).create(InitOptions(temp.resolve("nope")))
        assertIs<InitOutcome.Failed>(failed)
    }

    @Test
    fun `every bundled template is where the lists say, and nothing bundled is left out`() {
        val root = Paths.get(BundledInitTemplates::class.java.classLoader.getResource("graft/home")!!.toURI())
        val files = { dir: String ->
            Files.list(root.resolve(dir)).use { s -> s.map { it.fileName.toString() }.toList().sorted() }
        }
        assertEquals(bundled.gitignores.map { "$it.gitignore" }.sorted(), files("gitignore"))
        assertEquals(bundled.licenses.map { "${it.id}.txt" }.sorted(), files("licenses"))
        // No Windows line endings. The macOS template's two lone CRs stay: `Icon[\r]` is how it names the "Icon\r" file.
        bundled.gitignores.forEach {
            assertTrue(bundled.gitignore(it)!!.isNotBlank() && "\r\n" !in bundled.gitignore(it)!!, it)
        }
        assertEquals(2, bundled.gitignore("macOS")!!.count { it == '\r' })
        assertNull(bundled.gitignore("../../etc/passwd"))
        assertNull(bundled.license("Not-A-License", 2026, "x"))
    }

    @Test
    fun `licenses fill in their year and holder, and leave the others as they are`() {
        assertTrue(bundled.license("BSD-3-Clause", 2026, "Ann")!!.startsWith("Copyright (c) 2026 Ann"))
        assertTrue(bundled.license("ISC", 2026, "Ann")!!.contains("Copyright 2026 Ann"))
        assertTrue(bundled.license("GPL-3.0-or-later", 2026, "Ann")!!.contains("GNU GENERAL PUBLIC LICENSE"))
        bundled.licenses.forEach { l ->
            val text = bundled.license(l.id, 2026, "Ann")!!
            assertEquals(l.needsHolder, "Ann" in text, l.id)
            assertTrue("<year>" !in text && "<owner>" !in text && "<copyright holders>" !in text, l.id)
        }
    }

    @Test
    fun `summaries run four at a time however many rows ask`() = runTest {
        access.summaryDelayMs = 50
        val summaries = GitRepositorySummaryUseCase(access)
        (1..12).map { async { summaries.summarize(temp.resolve("r$it")) } }.awaitAll()
        assertEquals(4, access.mostSummariesAtOnce.get())
    }

    @Test
    fun `init records the new repository`() = runTest {
        val folder = temp.resolve("new")
        val init = GitInitRepositoryUseCase(access, known)
        assertEquals(folder, init.init(folder, "trunk").getOrThrow().workTree)
        assertEquals(listOf(folder to "trunk"), access.inits)
        assertEquals(listOf(folder), known.opened)
    }
}

/** A failure as git's runner returns one, by error type. */
private class FakeGitError(type: String) : SinewException("GIT", ExceptionLayer.Repository, "OPEN") {
    override val typeCode: String = type
    override val errorMessage: ErrorMessage = ErrorMessage.Local(key = "test", fallback = type)
}

private class FakeAccess : RepositoryAccessRepository {
    val locations = HashMap<Path, RepositoryLocation>()
    val untrusted = HashMap<Path, String>()
    val errors = HashMap<Path, SinewException>()
    val trusted = ArrayList<String>()
    val inits = ArrayList<Pair<Path, String>>()
    val commits = ArrayList<Triple<Path, List<String>, String>>()
    var commitFails = false
    var initFails = false
    var summaryDelayMs = 0L
    private val running = java.util.concurrent.atomic.AtomicInteger()
    val mostSummariesAtOnce = java.util.concurrent.atomic.AtomicInteger()
    var trustFails = false
    var cloneSteps: List<CloneProgress> = emptyList()
    var cloneFails = false

    private class Untrusted(val folder: String) : SinewException("GIT", ExceptionLayer.Repository, "OPEN") {
        override val typeCode: String = GraftCodes.Types.UNSAFE_OWNER
        override val errorMessage: ErrorMessage = ErrorMessage.Local(key = "test", fallback = "untrusted")
    }

    override suspend fun locate(folder: Path): Result<RepositoryLocation> {
        locations[folder]?.let { return Result.Success(it) }
        untrusted[folder]?.let { return Result.Failure(Untrusted(it)) }
        return Result.Failure(errors[folder] ?: FakeGitError(GraftCodes.Types.NOT_FOUND))
    }

    override suspend fun summary(workTree: Path): Result<RepositorySummary> {
        val now = running.incrementAndGet()
        mostSummariesAtOnce.accumulateAndGet(now, ::maxOf)
        kotlinx.coroutines.delay(summaryDelayMs)
        running.decrementAndGet()
        return Result.Success(
            RepositorySummary(com.srctool.graft.data.git.api.models.Head.Branch("main", null), null, false),
        )
    }

    override suspend fun init(folder: Path, defaultBranch: String): Result<RepositoryLocation> {
        inits.add(folder to defaultBranch)
        if (initFails) return Result.Failure(FakeGitError(GraftCodes.Types.EXIT))
        Files.createDirectories(folder)
        return Result.Success(RepositoryLocation(folder, folder.resolve(".git"), folder.resolve(".git")))
    }

    override suspend fun initialCommit(workTree: Path, paths: List<String>, message: String): Result<Unit> {
        commits.add(Triple(workTree, paths, message))
        return if (commitFails) Result.Failure(FakeGitError(GraftCodes.Types.EXIT)) else Result.Success(Unit)
    }

    override fun clone(request: CloneRequest): Flow<CloneProgress> = flow {
        if (cloneFails) throw FakeGitError(GraftCodes.Types.AUTH)
        cloneSteps.forEach { emit(it) }
    }

    override suspend fun trust(folder: String): Result<Unit> {
        if (trustFails) return Result.Failure(FakeGitError(GraftCodes.Types.EXIT))
        trusted.add(folder)
        untrusted.values.remove(folder)
        return Result.Success(Unit)
    }

    override fun untrustedFolder(error: Throwable): String? = (error as? Untrusted)?.folder
}

private class FakeKnown : KnownRepositoryRepository {
    val opened = ArrayList<Path>()
    val listed = ArrayList<Path>()

    override suspend fun list(): Result<Saved<List<KnownRepository>>> =
        Result.Success(Saved(listed.map { KnownRepository(it, 0, false, false) }, SavedStatus.Current))

    override suspend fun opened(path: Path): Result<Unit> {
        opened.add(path)
        return Result.Success(Unit)
    }

    override suspend fun setPinned(path: Path, pinned: Boolean): Result<Unit> = error("not used")

    override suspend fun remove(path: Path): Result<Unit> = error("not used")

    override suspend fun relocate(from: Path, to: Path): Result<Unit> = error("not used")
}

private class FakeScanner(private val found: List<Path>) : FolderScanRepository {
    override suspend fun scan(folder: Path, maxDepth: Int): Result<List<Path>> = Result.Success(found)
}

private class FakeWorkspaces : WorkspaceRepository {
    override suspend fun list(): Result<Saved<List<Workspace>>> = error("not used")

    override suspend fun create(name: String, repositories: List<Path>): Result<Workspace> =
        Result.Success(Workspace("w1", name, repositories))

    override suspend fun rename(id: String, name: String): Result<Workspace> = error("not used")

    override suspend fun add(id: String, repositories: List<Path>): Result<Workspace> = error("not used")

    override suspend fun removeRepository(id: String, repository: Path): Result<Workspace> = error("not used")

    override suspend fun delete(id: String): Result<Unit> = error("not used")
}
