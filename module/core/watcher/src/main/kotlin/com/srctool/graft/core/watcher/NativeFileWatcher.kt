// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.watcher

import io.methvin.watcher.DirectoryChangeEvent
import io.methvin.watcher.DirectoryChangeListener
import io.methvin.watcher.DirectoryWatcher
import io.methvin.watcher.visitor.FileTreeVisitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import org.slf4j.helpers.NOPLogger
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [FileWatcher] on `directory-watcher` (Libraries): FSEvents on macOS, the JDK's watch service on Windows and Linux.
 * `.git/objects`, other worktrees and the folders [ignore] names aren't watched at all, which keeps Linux under its
 * inotify limit on large repositories. When native watching can't start, the repository is polled every
 * [pollInterval] instead (Platform Integration 3.1).
 */
public class NativeFileWatcher private constructor(
    private val ignore: (Path) -> Boolean,
    private val pollInterval: Duration,
    private val hooks: Hooks,
) : FileWatcher {
    public constructor(ignore: (Path) -> Boolean = { false }, pollInterval: Duration = 5.seconds) :
        this(ignore, pollInterval, Hooks())

    /** Seams for tests: how the native watch starts, and a call when it's closed. */
    internal class Hooks(
        val startNative: (
            roots: List<Path>,
            listener: DirectoryChangeListener,
            visitor: FileTreeVisitor,
        ) -> DirectoryWatcher =
            ::startDirectoryWatcher,
        val onClosed: () -> Unit = {},
    )

    override fun watch(repository: WatchedRepository): Flow<RepoChange> = rawChanges(repository).coalesce()

    private fun rawChanges(repository: WatchedRepository): Flow<RawChange> = channelFlow {
        val repo = repository.real()
        val classifier = ChangeClassifier(repo, ignore)
        val failed = CompletableDeferred<Throwable>()
        val closing = AtomicBoolean(false)
        val executor = watcherThread()
        var native: DirectoryWatcher? = null

        val listener =
            object : DirectoryChangeListener {
                override fun onEvent(event: DirectoryChangeEvent) {
                    if (event.eventType() == DirectoryChangeEvent.EventType.OVERFLOW) {
                        trySend(RawChange.Overflow)
                        return
                    }
                    val path = event.path()?.toAbsolutePath()?.normalize() ?: return
                    classifier.classify(path)?.let { trySend(RawChange.Path(it)) }
                }
            }

        try {
            native = hooks.startNative(repo.roots(), listener, SkippingVisitor(classifier))
            native.watchAsync(executor).whenComplete { _, error ->
                if (error != null && !closing.get()) failed.complete(error.unwrap())
            }
        } catch (e: IOException) {
            failed.complete(e)
        } catch (e: UncheckedIOException) {
            failed.complete(e.cause ?: e)
        }

        launch {
            val error = failed.await()
            closing.set(true)
            runCatching { native?.close() }
            send(RawChange.Fallback(reasonOf(error), error.message.orEmpty()))
            pollChanges(repo, classifier, pollInterval).collect { send(it) }
        }
        launch { wakeChecks().collect { send(it) } }

        awaitClose {
            closing.set(true)
            runCatching { native?.close() }
            executor.shutdownNow()
            hooks.onClosed()
        }
    }

    internal companion object {
        /** A watcher whose native start and close can be observed or replaced. */
        fun withHooks(
            ignore: (Path) -> Boolean = { false },
            pollInterval: Duration = 5.seconds,
            startNative: (
                List<Path>,
                DirectoryChangeListener,
                FileTreeVisitor,
            ) -> DirectoryWatcher = ::startDirectoryWatcher,
            onClosed: () -> Unit = {},
        ): NativeFileWatcher = NativeFileWatcher(ignore, pollInterval, Hooks(startNative, onClosed))

        fun startDirectoryWatcher(
            roots: List<Path>,
            listener: DirectoryChangeListener,
            visitor: FileTreeVisitor,
        ): DirectoryWatcher = DirectoryWatcher
            .builder()
            .paths(roots)
            .listener(listener)
            .fileTreeVisitor(visitor)
            // Hashing would read every file of the working tree; git decides what really changed.
            .fileHashing(false)
            .logger(NOPLogger.NOP_LOGGER)
            .build()

        fun watcherThread(): ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "graft-file-watcher").apply {
                isDaemon =
                    true
            }
        }

        fun reasonOf(error: Throwable): RepoChange.PollingFallback.Reason {
            val text = generateSequence(error) { it.cause }.joinToString(" ") { it.message.orEmpty() }
            val limit = listOf("inotify", "User limit", "too many open files", "No space left on device")
            return if (limit.any { text.contains(it, ignoreCase = true) }) {
                RepoChange.PollingFallback.Reason.WATCH_LIMIT
            } else {
                RepoChange.PollingFallback.Reason.UNSUPPORTED
            }
        }

        private fun Throwable.unwrap(): Throwable = if ((this is CompletionException || this is UncheckedIOException) &&
            cause != null
        ) {
            cause!!.unwrap()
        } else {
            this
        }
    }
}

/** The repository's folders with symlinks resolved, so they match the paths the OS reports. */
private fun WatchedRepository.real(): WatchedRepository {
    fun Path.real(): Path = runCatching { toRealPath() }.getOrElse { toAbsolutePath().normalize() }
    return WatchedRepository(workTree?.real(), gitDir.real(), commonDir.real())
}

/**
 * The folders to register: the working tree, plus the git dirs that aren't inside it. A linked worktree's git dir is
 * its own root, because the common dir's `worktrees/` folder is skipped.
 */
private fun WatchedRepository.roots(): List<Path> {
    val work = workTree
    val gitRoots = listOf(commonDir, gitDir).filterNot { work != null && it.startsWith(work) }
    return (listOfNotNull(work) + gitRoots).distinct()
}

/** Walks the tree for registration, skipping what [classifier] says is never watched. */
private class SkippingVisitor(private val classifier: ChangeClassifier) : FileTreeVisitor {
    override fun recursiveVisitFiles(
        file: Path,
        onDirectory: FileTreeVisitor.Callback,
        onFile: FileTreeVisitor.Callback,
    ) {
        Files.walkFileTree(
            file,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != file && classifier.skipsFolder(dir.toAbsolutePath().normalize())) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    onDirectory.call(dir)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(f: Path, attrs: BasicFileAttributes): FileVisitResult {
                    onFile.call(f)
                    return FileVisitResult.CONTINUE
                }

                // A folder that vanished or can't be read mid-walk isn't a reason to stop watching the rest.
                override fun visitFileFailed(f: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
    }
}

/**
 * The fallback: every [interval], the git files' times and sizes are compared with the last look, and the working tree
 * is assumed to have changed (status is cheap to refresh, and can't be detected without watching).
 */
private fun pollChanges(repo: WatchedRepository, classifier: ChangeClassifier, interval: Duration): Flow<RawChange> =
    flow {
        var previous = gitFiles(repo, classifier)
        while (true) {
            delay(interval)
            val now = gitFiles(repo, classifier)
            (now.keys + previous.keys)
                .filter { now[it] != previous[it] }
                .mapNotNull(classifier::classify)
                .distinct()
                .forEach { emit(RawChange.Path(it)) }
            if (repo.workTree != null) emit(RawChange.Path(ChangeKind.STATUS))
            previous = now
        }
    }.flowOn(Dispatchers.IO)

/** Every file in the git dirs that a change could matter for, with its modification time and size. */
private fun gitFiles(repo: WatchedRepository, classifier: ChangeClassifier): Map<Path, Pair<Long, Long>> {
    val files = HashMap<Path, Pair<Long, Long>>()
    for (root in listOf(repo.gitDir, repo.commonDir).distinct()) {
        if (!Files.isDirectory(root)) continue
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (dir != root &&
                        classifier.skipsFolder(dir)
                    ) {
                        FileVisitResult.SKIP_SUBTREE
                    } else {
                        FileVisitResult.CONTINUE
                    }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (classifier.classify(file) !=
                        null
                    ) {
                        files[file] = attrs.lastModifiedTime().toMillis() to attrs.size()
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
    }
    return files
}
