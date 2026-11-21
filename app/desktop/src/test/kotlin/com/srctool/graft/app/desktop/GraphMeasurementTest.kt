// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.app.desktop

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.srctool.graft.app.desktop.di.desktopModules
import com.srctool.graft.app.desktop.session.GitRepositorySession
import com.srctool.graft.core.configuration.AppFolders
import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.core.git.WriteQueue
import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.ui.theme.GraftTheme
import com.srctool.graft.core.watcher.FileWatcher
import com.srctool.graft.core.watcher.RepoChange
import com.srctool.graft.core.watcher.WatchedRepository
import com.srctool.graft.data.git.api.models.LogScope
import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.data.git.di.GitDataModule
import com.srctool.graft.navigation.shell.MainWindow
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.session.RepositorySessions
import com.srctool.graft.presentation.window.WindowState
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration.Companion.minutes

/**
 * G1 Task 13's measurements of the real graph screen (as G0 Task 8 measured the spike), and D10's HEAD-first numbers.
 * Manual: it runs only when `GRAFT_MEASURE_REPO` names a large repository, and prints `GRAFT_MEASURE …` lines. Git runs
 * in the test sandbox (temp HOME, no global config) on a shared bare clone; the repository itself is only read. Bare,
 * so there's no working tree to scan: the numbers are the graph's.
 */
@OptIn(ExperimentalTestApi::class)
class GraphMeasurementTest {
    private object NoWatcher : FileWatcher {
        override fun watch(repository: WatchedRepository): Flow<RepoChange> = emptyFlow()
    }

    private fun report(name: String, value: Any) = println("GRAFT_MEASURE $name=$value")

    private fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000

    private fun usedHeapMb(): Long {
        repeat(3) { System.gc() }
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    }

    @org.junit.jupiter.api.Test
    fun `the graph screen on a large repository`() {
        val repo = System.getenv("GRAFT_MEASURE_REPO")
        assumeTrue(repo != null, "set GRAFT_MEASURE_REPO to measure")
        val sandbox = GitSandbox(Files.createTempDirectory("graft-measure"))
        // Git runs only inside the sandbox: a shared, bare clone reads the repository's objects (and its commit-graph
        // file) through alternates without copying them or writing to the original.
        val path = sandbox.root.resolve("measured.git")
        sandbox.git(
            sandbox.root,
            "clone",
            "--quiet",
            "--bare",
            "--shared",
            Paths.get(repo!!).toAbsolutePath().toString(),
            path.toString(),
        )
        val git = GitDataModule(sandbox.runner())

        // D10: how soon the first screen of rows arrives from git, for every ref and for HEAD's history alone.
        runBlocking {
            for (round in 1..2) {
                val all = System.nanoTime()
                git.commits.log(path, LogScope.All()).rows.take(60).toList()
                report("d10.allRefs.first60Ms.round$round", ms(all))
                val head = System.nanoTime()
                git.commits.log(path, LogScope.Only(setOf("HEAD"))).rows.take(60).toList()
                report("d10.head.first60Ms.round$round", ms(head))
            }
        }

        val location = runBlocking { git.access.locate(path).getOrThrow() }
        val session = GitRepositorySession(location, git.refs, git.access, NoWatcher, WriteQueue())
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope.launch {
            session.init()
            session.listen(scope)
        }
        val rowLabel = SemanticsMatcher("a commit row") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any {
                it.startsWith("Commit ") &&
                    it.length > 20
            }
        }
        val loaded = SemanticsMatcher("the loaded count") { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t ->
                t.text.endsWith(" commits") && !t.text.startsWith("Loading") && t.text.first().isDigit()
            }
        }

        // The app's Koin wiring (ADR-28), with the sandbox's runner and folders in place of the app's own.
        val folders = sandbox.root.resolve("graft-app")
        startKoin {
            allowOverride(true)
            modules(
                desktopModules() +
                    module {
                        single<GitRunner> { sandbox.runner() }
                        single { AppFolders(folders, folders, folders.resolve("cache"), folders.resolve("logs")) }
                    },
            )
        }
        val windows = WindowState()
        val windowId = windows.openWindow()
        windows.openRepository(windowId, location)
        val sessions =
            object : RepositorySessions {
                override val sessions =
                    MutableStateFlow<Map<RepositoryLocation, RepositorySession>>(mapOf(location to session))
            }
        runDesktopComposeUiTest(1280, 900, testTimeout = 30.minutes) {
            val opened = System.nanoTime()
            setContent {
                GraftTheme { MainWindow(windows, windowId, sessions) }
            }
            waitUntil(timeoutMillis = 120_000) { onAllNodes(rowLabel).fetchSemanticsNodes().isNotEmpty() }
            report("screen.firstScreenMs", ms(opened))
            waitUntil(timeoutMillis = 900_000) { onAllNodes(loaded).fetchSemanticsNodes().isNotEmpty() }
            report("screen.loadedMs", ms(opened))
            report("screen.heapMb", usedHeapMb())

            // Scrolling: 40 rows a step, as G0 did, each step one rendered frame.
            val table = onNodeWithContentDescription("Commit graph", substring = true)
            val steps = 2_500
            val times = LongArray(steps)
            for (i in 0 until steps) {
                val t = System.nanoTime()
                table.performScrollToIndex(i * 40)
                times[i] = System.nanoTime() - t
            }
            times.sort()
            report("scroll.stepMeanMs", "%.2f".format(times.average() / 1e6))
            report("scroll.stepP95Ms", "%.2f".format(times[(steps * 0.95).toInt()] / 1e6))
            report("scroll.stepMaxMs", "%.2f".format(times.last() / 1e6))
        }
        scope.cancel()
        stopKoin()
    }
}
