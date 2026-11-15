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
import com.srctool.graft.feature.home.domain.api.models.OpenOutcome
import com.srctool.graft.feature.home.domain.api.models.ScannedRepository
import com.srctool.graft.feature.home.domain.implementation.usecases.GitCloneRepositoryUseCase
import com.srctool.graft.feature.home.domain.implementation.usecases.GitInitRepositoryUseCase
import com.srctool.graft.feature.home.domain.implementation.usecases.GitOpenRepositoryUseCase
import com.srctool.graft.feature.home.domain.implementation.usecases.GitScanFolderUseCase
import com.srctool.sinew.models.ErrorMessage
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.SinewException
import com.srctool.sinew.models.getOrThrow
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

    override suspend fun summary(workTree: Path): Result<RepositorySummary> = error("not used")

    override suspend fun init(folder: Path, defaultBranch: String): Result<RepositoryLocation> {
        inits.add(folder to defaultBranch)
        return Result.Success(RepositoryLocation(folder, folder.resolve(".git"), folder.resolve(".git")))
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
