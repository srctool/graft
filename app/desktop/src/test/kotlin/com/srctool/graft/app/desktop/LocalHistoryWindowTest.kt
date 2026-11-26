// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.app.desktop

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
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
import com.srctool.graft.data.app.api.repositories.SettingsRepository
import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.data.git.api.repositories.LocalHistoryRepository
import com.srctool.graft.data.git.api.repositories.RefRepository
import com.srctool.graft.data.git.api.repositories.RepositoryAccessRepository
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** G2's "a file deleted by an agent is restored": from the real window, over a real repository in a sandbox. */
@OptIn(ExperimentalTestApi::class)
class LocalHistoryWindowTest {
    private object Unwatched : FileWatcher {
        override fun watch(repository: WatchedRepository): Flow<RepoChange> = emptyFlow()
    }

    private fun ComposeUiTest.await(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        try {
            waitUntil(timeoutMillis = 20_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println(onRoot().printToString(maxDepth = 30))
            throw e
        }
    }

    @Test
    fun `a file deleted after a snapshot is restored from the screen`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "wedges the Windows CI runner")
        val sandbox = GitSandbox(Files.createTempDirectory("graft-history-window"))
        val repo = sandbox.repo { commit("first commit") { file("a.txt", "a") } }
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
        val koin = org.koin.mp.KoinPlatform.getKoin()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            runBlocking { koin.get<SettingsRepository>().update { it.copy(onboarded = true) } }
            // Never committed, snapshotted, then deleted: only Local History still has it.
            val notes = repo.resolve("notes.txt")
            Files.writeString(notes, "what the agent deleted\n")
            runBlocking { koin.get<LocalHistoryRepository>().snapshot(repo).getOrThrow() }
            Files.delete(notes)

            val access = koin.get<RepositoryAccessRepository>()
            val location = runBlocking { access.locate(repo).getOrThrow() }
            val session = GitRepositorySession(location, koin.get<RefRepository>(), access, Unwatched, WriteQueue())
            scope.launch {
                session.init()
                session.listen(scope)
            }
            val windows = WindowState()
            val windowId = windows.openWindow()
            windows.openRepository(windowId, location)
            val sessions =
                object : RepositorySessions {
                    override val sessions =
                        MutableStateFlow<Map<RepositoryLocation, RepositorySession>>(mapOf(location to session))
                }

            runDesktopComposeUiTest(1600, 900) {
                setContent { GraftTheme { MainWindow(windows, windowId, sessions) } }
                // The Changes window starts closed: its strip button opens it.
                await(hasContentDescription("Changes"))
                onNodeWithContentDescription("Changes").performClick()
                val open = hasContentDescription("Local History")
                await(open)
                onNodeWithContentDescription("Local History").performClick()
                await(hasText("Deleted files"))
                onNodeWithText("Deleted files").performClick()
                await(hasContentDescription("notes.txt, last seen", substring = true))
                await(hasText("Restore file"))
                waitUntil(timeoutMillis = 20_000) {
                    onNodeWithText("Restore file").performClick()
                    waitForIdle()
                    Files.exists(notes)
                }
            }
            assertEquals("what the agent deleted\n", Files.readString(notes))
        } finally {
            scope.cancel()
            stopKoin()
        }
    }
}
