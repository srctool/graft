// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.watcher

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The watcher on real folders, on every OS in CI. The repository is a plain-file `.git` layout: no git process runs.
 * Registration is asynchronous, so each test writes until the event arrives instead of assuming the watch is ready.
 */
class NativeFileWatcherTest {
    @TempDir
    lateinit var tmp: Path

    private lateinit var work: Path
    private lateinit var git: Path

    private fun layout(): WatchedRepository {
        work = Files.createDirectories(tmp.resolve("repo with spaces"))
        git = Files.createDirectories(work.resolve(".git"))
        Files.createDirectories(git.resolve("refs/heads"))
        Files.createDirectories(git.resolve("objects/ab"))
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n")
        Files.writeString(git.resolve("refs/heads/main"), "0".repeat(40) + "\n")
        Files.writeString(git.resolve("index"), "DIRC")
        Files.createDirectories(work.resolve("src"))
        return WatchedRepository(work, git, git)
    }

    /** Collects [watcher]'s changes for [repo] into a channel for the length of [block]. */
    private fun watching(
        watcher: FileWatcher,
        repo: WatchedRepository,
        block: suspend CoroutineScope.(Channel<RepoChange>) -> Unit,
    ) = runBlocking {
        val changes = Channel<RepoChange>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Default) { watcher.watch(repo).collect { changes.send(it) } }
        try {
            withTimeout(60.seconds) { block(changes) }
        } finally {
            job.cancel()
            job.join()
        }
    }

    /** Writes [file] every 300 ms until a change matching [expected] arrives. */
    private suspend fun Channel<RepoChange>.writeUntil(file: Path, expected: (RepoChange) -> Boolean): RepoChange {
        val n = AtomicInteger()
        repeat(60) {
            Files.writeString(file, "change ${n.incrementAndGet()}\n")
            val got = withTimeoutOrNull(300.milliseconds) { receive() }
            if (got != null && expected(got)) return got
        }
        error("no change for $file after 18 s")
    }

    @Test
    fun `a branch moving is a refs change`() = watching(NativeFileWatcher(), layout()) { changes ->
        changes.writeUntil(git.resolve("refs/heads/main")) { it == RepoChange.RefsChanged }
    }

    @Test
    fun `a new branch file in a new folder is a refs change`() = watching(NativeFileWatcher(), layout()) { changes ->
        val folder = Files.createDirectories(git.resolve("refs/heads/feature"))
        changes.writeUntil(folder.resolve("login")) { it == RepoChange.RefsChanged }
    }

    @Test
    fun `editing a working-tree file is a status change`() = watching(NativeFileWatcher(), layout()) { changes ->
        changes.writeUntil(work.resolve("src/Main.kt")) { it == RepoChange.StatusChanged }
    }

    @Test
    fun `a merge in progress is an operation change`() = watching(NativeFileWatcher(), layout()) { changes ->
        changes.writeUntil(git.resolve("MERGE_HEAD")) { it == RepoChange.OperationChanged }
    }

    @Test
    fun `writing objects alone causes no refresh`() = watching(NativeFileWatcher(), layout()) { changes ->
        // Wait until the watch is live, then drain.
        changes.writeUntil(work.resolve("src/ready.txt")) { it == RepoChange.StatusChanged }
        delay(1.seconds)
        while (changes.tryReceive().isSuccess) Unit
        repeat(5) { Files.writeString(git.resolve("objects/ab/obj$it"), "blob") }
        Files.writeString(git.resolve("index.lock"), "lock")
        assertEquals(null, withTimeoutOrNull(2.seconds) { changes.receive() })
    }

    @Test
    fun `when the native watch can't start, it polls instead and says why`() {
        val repo = layout()
        val failing =
            NativeFileWatcher.withHooks(
                pollInterval = 200.milliseconds,
                startNative = { _, _, _ -> throw IOException("User limit of inotify watches reached") },
            )
        watching(failing, repo) { changes ->
            val fallback = assertIs<RepoChange.PollingFallback>(withTimeout(10.seconds) { changes.receive() })
            assertEquals(RepoChange.PollingFallback.Reason.WATCH_LIMIT, fallback.reason)
            changes.writeUntil(git.resolve("HEAD")) { it == RepoChange.RefsChanged }
        }
    }

    @Test
    fun `stopping the collection closes the native watch`() {
        val closed = AtomicInteger()
        val watcher = NativeFileWatcher.withHooks(onClosed = { closed.incrementAndGet() })
        watching(watcher, layout()) { changes ->
            changes.writeUntil(git.resolve("HEAD")) { it == RepoChange.RefsChanged }
        }
        assertTrue(closed.get() == 1, "closed ${closed.get()} times")
    }
}
