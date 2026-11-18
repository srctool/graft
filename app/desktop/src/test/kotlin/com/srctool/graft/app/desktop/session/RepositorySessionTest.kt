// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.app.desktop.session

import com.srctool.graft.core.git.WriteQueue
import com.srctool.graft.core.watcher.FileWatcher
import com.srctool.graft.core.watcher.RepoChange
import com.srctool.graft.core.watcher.WatchedRepository
import com.srctool.graft.data.git.api.models.CloneProgress
import com.srctool.graft.data.git.api.models.CloneRequest
import com.srctool.graft.data.git.api.models.Head
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.Ref
import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.data.git.api.models.RepositorySummary
import com.srctool.graft.data.git.api.repositories.RefRepository
import com.srctool.graft.data.git.api.repositories.RepositoryAccessRepository
import com.srctool.graft.presentation.session.SnapshotPart
import com.srctool.graft.presentation.window.WindowState
import com.srctool.sinew.models.ErrorMessage
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.SinewException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositorySessionTest {
    private val app = location("/work/app")
    private val watcher = FakeWatcher()
    private val refs = FakeRefs()
    private val access = FakeAccess()

    private fun location(path: String, bare: Boolean = false): RepositoryLocation {
        val root = Paths.get(path)
        return if (bare) {
            RepositoryLocation(
                null,
                root,
                root,
            )
        } else {
            RepositoryLocation(root, root.resolve(".git"), root.resolve(".git"))
        }
    }

    private fun session(at: RepositoryLocation = app) = GitRepositorySession(at, refs, access, watcher, WriteQueue())

    private fun TestScope.started(at: RepositoryLocation = app): GitRepositorySession = session(at).also {
        it.listen(backgroundScope)
        runCurrent()
    }

    @Test
    fun `a new session reads refs and status`() = runTest {
        val session = started()
        val snapshot = session.snapshot.value
        assertEquals(setOf(SnapshotPart.REFS, SnapshotPart.STATUS), snapshot.loaded)
        assertEquals(listOf("refs/heads/main"), snapshot.refs.map { it.name })
        assertEquals("main", (snapshot.head as Head.Branch).name)
        assertEquals(emptySet(), snapshot.refreshing)
        assertEquals(1, watcher.active[app])
    }

    @Test
    fun `a ref change reads refs and status, a status change only status`() = runTest {
        started()
        refs.names = listOf("refs/heads/main", "refs/heads/topic")
        watcher.emit(app, RepoChange.RefsChanged)
        runCurrent()
        assertEquals(2, refs.calls)
        assertEquals(2, access.summaries)

        watcher.emit(app, RepoChange.StatusChanged)
        runCurrent()
        assertEquals(2, refs.calls)
        assertEquals(3, access.summaries)
    }

    @Test
    fun `changes during a read queue exactly one more read`() = runTest {
        val session = started()
        val gate = CompletableDeferred<Unit>()
        refs.gate = gate
        watcher.emit(app, RepoChange.RefsChanged)
        runCurrent()
        assertTrue(SnapshotPart.REFS in session.snapshot.value.refreshing)
        repeat(5) { watcher.emit(app, RepoChange.RefsChanged) }
        runCurrent()
        refs.gate = null
        gate.complete(Unit)
        runCurrent()
        assertEquals(3, refs.calls, "the first read, the one that waited, and one for the burst")
    }

    @Test
    fun `a failed read keeps the last refs and its error until a read succeeds`() = runTest {
        val session = started()
        refs.fail = true
        session.refresh(setOf(SnapshotPart.REFS))
        runCurrent()
        assertNotNull(session.snapshot.value.failures[SnapshotPart.REFS])
        assertEquals(listOf("refs/heads/main"), session.snapshot.value.refs.map { it.name })

        refs.fail = false
        session.refresh(setOf(SnapshotPart.REFS))
        runCurrent()
        assertNull(session.snapshot.value.failures[SnapshotPart.REFS])
    }

    @Test
    fun `a watcher that falls back to polling says so`() = runTest {
        val session = started()
        watcher.emit(app, RepoChange.PollingFallback(RepoChange.PollingFallback.Reason.entries.first(), "limit"))
        runCurrent()
        assertTrue(session.snapshot.value.polling)
        assertEquals(2, refs.calls)
    }

    @Test
    fun `a bare repository has refs but no status`() = runTest {
        val bare = location("/srv/app.git", bare = true)
        val session = started(bare)
        assertEquals(0, access.summaries)
        assertNull(session.snapshot.value.summary)
        assertTrue(SnapshotPart.STATUS in session.snapshot.value.loaded)
        assertEquals(Paths.get("/srv/app.git"), refs.lastRoot)
    }

    @Test
    fun `writes run one at a time`() = runTest {
        val session = started()
        val first = CompletableDeferred<Unit>()
        val order = ArrayList<String>()
        val a = async {
            session.write {
                order.add("a start")
                first.await()
                order.add("a end")
            }
        }
        val b = async { session.write { order.add("b") } }
        runCurrent()
        assertEquals(listOf("a start"), order)
        first.complete(Unit)
        a.await()
        b.await()
        assertEquals(listOf("a start", "a end", "b"), order)
    }

    @Test
    fun `a session opens with a repository's first tab and closes with its last`() = runTest {
        val windows = WindowState()
        val service = RepositorySessionsService(windows) { session(it) }
        service.listen(backgroundScope)
        val window = windows.openWindow()
        val tab = windows.openRepository(window, app)
        runCurrent()
        assertEquals(setOf(app), service.sessions.value.keys)
        assertEquals(1, watcher.active[app])

        // The same repository again only brings its tab to the front.
        val other = windows.openWindow()
        assertEquals(tab, windows.openRepository(other, app))
        runCurrent()
        assertEquals(1, service.sessions.value.size)

        // A read still running when the tab closes is cancelled with the watcher.
        refs.gate = CompletableDeferred()
        service.sessions.value.getValue(app).refresh(setOf(SnapshotPart.REFS))
        runCurrent()
        windows.closeTab(tab)
        runCurrent()
        assertEquals(emptyMap(), service.sessions.value)
        assertEquals(0, watcher.active[app])
        assertTrue(refs.cancelled, "the running git read was cancelled")
    }

    @Test
    fun `a window coming to the front reads its repositories again`() = runTest {
        val windows = WindowState()
        val service = RepositorySessionsService(windows) { session(it) }
        service.listen(backgroundScope)
        val window = windows.openWindow()
        windows.openRepository(window, app)
        runCurrent()
        val before = refs.calls to access.summaries
        service.windowFocused(window)
        runCurrent()
        assertEquals(before.first + 1 to before.second + 1, refs.calls to access.summaries)
        assertFalse(service.sessions.value.getValue(app).snapshot.value.polling)
    }
}

private class TestGitError : SinewException("GIT", ExceptionLayer.Repository, "REFS") {
    override val typeCode: String = "EXIT"
    override val errorMessage: ErrorMessage = ErrorMessage.Local(key = "test", fallback = "failed")
}

/** One change stream per repository, and how many watches of each are running. */
private class FakeWatcher : FileWatcher {
    private val flows = HashMap<Path, MutableSharedFlow<RepoChange>>()
    val active = HashMap<RepositoryLocation, Int>()

    private fun flow(gitDir: Path) = flows.getOrPut(gitDir) { MutableSharedFlow(extraBufferCapacity = 64) }

    suspend fun emit(location: RepositoryLocation, change: RepoChange) = flow(location.gitDir).emit(change)

    override fun watch(repository: WatchedRepository): Flow<RepoChange> {
        val location = RepositoryLocation(repository.workTree, repository.gitDir, repository.commonDir)
        return flow(repository.gitDir)
            .onStart { active[location] = (active[location] ?: 0) + 1 }
            .onCompletion { active[location] = active.getValue(location) - 1 }
    }
}

private class FakeRefs : RefRepository {
    var names = listOf("refs/heads/main")
    var calls = 0
    var fail = false
    var gate: CompletableDeferred<Unit>? = null
    var cancelled = false
    var lastRoot: Path? = null

    override suspend fun refs(repository: Path): Result<List<Ref>> {
        calls++
        lastRoot = repository
        gate?.let { g ->
            try {
                g.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
        }
        if (fail) return Result.Failure(TestGitError())
        return Result.Success(names.map { Ref(it, ObjectId("a".repeat(40)), isHead = it == "refs/heads/main") })
    }
}

private class FakeAccess : RepositoryAccessRepository {
    var summaries = 0

    override suspend fun summary(workTree: Path): Result<RepositorySummary> {
        summaries++
        return Result.Success(
            RepositorySummary(Head.Branch("main", ObjectId("a".repeat(40))), upstream = null, dirty = false),
        )
    }

    override suspend fun locate(folder: Path): Result<RepositoryLocation> = error("not used")

    override suspend fun init(folder: Path, defaultBranch: String): Result<RepositoryLocation> = error("not used")

    override suspend fun initialCommit(workTree: Path, paths: List<String>, message: String): Result<Unit> =
        error("not used")

    override fun clone(request: CloneRequest): Flow<CloneProgress> = error("not used")

    override suspend fun trust(folder: String): Result<Unit> = error("not used")

    override fun untrustedFolder(error: Throwable): String? = null
}
