// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.app.desktop

import com.srctool.graft.app.desktop.di.desktopModules
import com.srctool.graft.core.configuration.AppFolders
import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.data.app.api.repositories.FolderScanRepository
import com.srctool.graft.data.app.api.repositories.KnownRepositoryRepository
import com.srctool.graft.data.app.api.repositories.RepositoryUiStateRepository
import com.srctool.graft.data.app.api.repositories.SettingsRepository
import com.srctool.graft.data.app.api.repositories.WindowStateRepository
import com.srctool.graft.data.app.api.repositories.WorkspaceRepository
import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.data.git.api.repositories.CommitRepository
import com.srctool.graft.data.git.api.repositories.CompareRepository
import com.srctool.graft.data.git.api.repositories.DiffRepository
import com.srctool.graft.data.git.api.repositories.IdentityRepository
import com.srctool.graft.data.git.api.repositories.RefRepository
import com.srctool.graft.data.git.api.repositories.ReflogRepository
import com.srctool.graft.data.git.api.repositories.RemoteRepository
import com.srctool.graft.data.git.api.repositories.RepositoryAccessRepository
import com.srctool.graft.data.git.api.repositories.StashRepository
import com.srctool.graft.presentation.session.RepoSnapshot
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.session.SnapshotPart
import com.srctool.graft.presentation.view.RepositoryViewState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.jupiter.api.io.TempDir
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The desktop app's Koin wiring (ADR-28) resolves: every repository the data modules bind, and every view model the
 * features declare, with the per-tab parameters the destinations pass. A binding left out fails here, not when a
 * screen opens. Graft's folders and the git runner are the test's own; nothing reaches the developer's.
 */
class KoinWiringTest {
    @TempDir
    lateinit var temp: Path

    private fun koin(): Koin {
        val sandbox = GitSandbox(Files.createDirectories(temp.resolve("git")))
        val folders = temp.resolve("graft")
        val overrides =
            module {
                single<GitRunner> { sandbox.runner() }
                single { AppFolders(folders, folders, folders.resolve("cache"), folders.resolve("logs")) }
            }
        return koinApplication {
            allowOverride(true)
            modules(desktopModules + overrides)
        }.koin
    }

    private val session =
        object : RepositorySession {
            override val location = RepositoryLocation(Path.of("/repo"), Path.of("/repo/.git"), Path.of("/repo/.git"))
            override val snapshot: StateFlow<RepoSnapshot> = MutableStateFlow(RepoSnapshot(location))

            override fun refresh(parts: Set<SnapshotPart>) = Unit

            override suspend fun <T> write(block: suspend () -> T): T = block()
        }

    @Test
    fun `every repository the data modules bind resolves`() {
        val koin = koin()
        val types: List<KClass<*>> =
            listOf(
                CommitRepository::class, RefRepository::class, RepositoryAccessRepository::class, DiffRepository::class,
                CompareRepository::class, ReflogRepository::class, StashRepository::class, RemoteRepository::class,
                IdentityRepository::class, SettingsRepository::class, KnownRepositoryRepository::class,
                WindowStateRepository::class, WorkspaceRepository::class, FolderScanRepository::class,
                RepositoryUiStateRepository::class,
            )
        types.forEach { koin.get<Any>(it) }
        // One instance each, shared by every screen.
        assertEquals(koin.get<CommitRepository>(), koin.get<CommitRepository>())
    }

    @Test
    fun `every view model resolves with the parameters its destination passes`() {
        val koin = koin()
        val folder = Path.of("/repo")
        val shared = RepositoryViewState(session.location.commonDir, koin.get())
        val withSession = parametersOf(folder, session, shared)
        val names =
            listOf(
                "presentation.graph.GraphViewModel",
                "presentation.branches.BranchesViewModel",
                "presentation.details.DetailsViewModel",
                "presentation.compare.CompareViewModel",
            ).map { "com.srctool.graft.feature.graph.$it" }
        for (name in names) {
            val viewModel = koin.get<Any>(Class.forName(name).kotlin, parameters = { withSession })
            assertEquals(name, viewModel::class.qualifiedName)
        }
        val diffType = Class.forName("com.srctool.graft.feature.graph.presentation.diff.DiffScreenViewModel").kotlin
        val diff = koin.get<Any>(diffType, parameters = { parametersOf(folder, shared) })
        assertEquals("DiffScreenViewModel", diff::class.simpleName)
    }
}
