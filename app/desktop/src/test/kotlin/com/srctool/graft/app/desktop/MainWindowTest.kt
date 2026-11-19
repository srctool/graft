// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.app.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
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
import com.srctool.graft.data.git.api.repositories.RefRepository
import com.srctool.graft.data.git.api.repositories.RepositoryAccessRepository
import com.srctool.graft.navigation.shell.MainWindow
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.session.RepositorySessions
import com.srctool.graft.presentation.window.TabContent
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
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real window over the app's real wiring (ADR-28), with git and Graft's folders in a sandbox: Home first, a
 * repository tab with its graph, and the keys that move between them.
 */
@OptIn(ExperimentalTestApi::class)
class MainWindowTest {
    private object NoWatcher : FileWatcher {
        override fun watch(repository: WatchedRepository): Flow<RepoChange> = emptyFlow()
    }

    @Test
    fun `Home opens first, a repository gets its own tab with its graph, and Mod+T comes back`() {
        val sandbox = GitSandbox(java.nio.file.Files.createTempDirectory("graft-window"))
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
        // A first run opens the welcome steps over Home; this test is about the window, so they're done.
        runBlocking { koin.get<SettingsRepository>().update { it.copy(onboarded = true) } }
        val access = koin.get<RepositoryAccessRepository>()
        val location = runBlocking { access.locate(repo).getOrThrow() }
        val session = GitRepositorySession(location, koin.get<RefRepository>(), access, NoWatcher, WriteQueue())
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
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
                    MutableStateFlow<Map<com.srctool.graft.data.git.api.models.RepositoryLocation, RepositorySession>>(
                        mapOf(location to session),
                    )
            }

        runDesktopComposeUiTest(1280, 800) {
            setContent { GraftTheme { MainWindow(windows, windowId, sessions) } }
            // The repository tab is in front: its graph reads the commit.
            val row = hasContentDescription("first commit", substring = true)
            try {
                waitUntil(timeoutMillis = 20_000) { onAllNodes(row).fetchSemanticsNodes().isNotEmpty() }
            } catch (e: Throwable) {
                println(onRoot().printToString(maxDepth = 12))
                throw e
            }
            onNodeWithContentDescription("Toolbar").assertExists()
            assertEquals("repo", repo.fileName.toString())

            // Mod+T goes Home; the repository tab is still there.
            onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.T) } }
            waitForIdle()
            assertEquals(TabContent.Home, windows.windows.value.single().activeTab.content)
            assertEquals(2, windows.windows.value.single().tabs.size)

            // Mod+Shift+] steps to the next tab, which wraps to the repository's.
            onRoot().performKeyInput {
                withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.RightBracket) } }
            }
            waitForIdle()
            assertTrue(windows.windows.value.single().activeTab.content is TabContent.Repository)

            // Mod+W closes it, leaving Home.
            onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.W) } }
            waitForIdle()
            assertEquals(listOf(TabContent.Home), windows.windows.value.single().tabs.map { it.content })
        }
        scope.cancel()
        stopKoin()
    }
}
