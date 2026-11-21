// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.app.implementation.repositories

import com.srctool.graft.data.app.api.models.KnownRepository
import com.srctool.graft.data.app.api.models.Saved
import com.srctool.graft.data.app.api.models.SavedWindow
import com.srctool.graft.data.app.api.repositories.KnownRepositoryRepository
import com.srctool.graft.data.app.api.repositories.WindowStateRepository
import com.srctool.graft.data.app.implementation.source.local.AppStateRecord
import com.srctool.graft.data.app.implementation.source.local.JsonStore
import com.srctool.graft.data.app.implementation.source.local.RepositoryFolders
import com.srctool.graft.data.app.implementation.source.local.RepositoryRecord
import com.srctool.graft.data.app.implementation.source.local.WindowRecord
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

internal class FileKnownRepositoryRepository(
    private val store: JsonStore<AppStateRecord>,
    private val clock: () -> Instant,
) : KnownRepositoryRepository {
    override suspend fun list(): Result<Saved<List<KnownRepository>>> = store.read().map { saved ->
        val repositories =
            withContext(Dispatchers.IO) {
                saved.value.repositories.ordered().map {
                    val path = Paths.get(it.path)
                    KnownRepository(
                        path,
                        it.lastOpened,
                        it.pinned,
                        missing = !RepositoryFolders.looksLikeRepository(path),
                    )
                }
            }
        Saved(repositories, saved.status)
    }

    override suspend fun opened(path: Path): Result<Unit> {
        val real = withContext(Dispatchers.IO) { RepositoryFolders.real(path) }.toString()
        val now = clock().epochSecond
        return store.update { state ->
            val existing = state.repositories.firstOrNull { it.path == real }
            val entry = RepositoryRecord(real, now, existing?.pinned ?: false)
            state.copy(repositories = trim(listOf(entry) + state.repositories.filter { it.path != real }))
        }.map { }
    }

    override suspend fun setPinned(path: Path, pinned: Boolean): Result<Unit> {
        val key = key(path)
        return store.update { state ->
            state.copy(repositories = state.repositories.map { if (it.path == key) it.copy(pinned = pinned) else it })
        }.map { }
    }

    override suspend fun remove(path: Path): Result<Unit> {
        val key = key(path)
        return store.update { state -> state.copy(repositories = state.repositories.filter { it.path != key }) }.map { }
    }

    override suspend fun relocate(from: Path, to: Path): Result<Unit> {
        val old = key(from)
        val new = withContext(Dispatchers.IO) { RepositoryFolders.real(to) }.toString()
        return store.update { state ->
            val moved = state.repositories.firstOrNull { it.path == old } ?: return@update state
            // Located onto a folder that's already listed: one entry, pinned if either was, at the later time.
            val other = state.repositories.firstOrNull { it.path == new }
            val merged =
                RepositoryRecord(
                    new,
                    maxOf(moved.lastOpened, other?.lastOpened ?: 0),
                    moved.pinned || other?.pinned == true,
                )
            state.copy(
                repositories = state.repositories.mapNotNull {
                    when (it.path) {
                        old -> merged
                        new -> null
                        else -> it
                    }
                },
            )
        }.map { }
    }

    /** A stored path is already real; one that no longer exists can only be matched as given. */
    private suspend fun key(path: Path): String =
        withContext(Dispatchers.IO) { RepositoryFolders.real(path) }.toString()

    private fun List<RepositoryRecord>.ordered(): List<RepositoryRecord> =
        sortedWith(compareByDescending<RepositoryRecord> { it.pinned }.thenByDescending { it.lastOpened })

    /** Pinned repositories always stay; of the others, the [MAX_RECENT] opened last. */
    private fun trim(repositories: List<RepositoryRecord>): List<RepositoryRecord> {
        val keep = repositories.filter { !it.pinned }.sortedByDescending { it.lastOpened }.take(MAX_RECENT).toSet()
        return repositories.filter { it.pinned || it in keep }
    }

    private companion object {
        const val MAX_RECENT = 30
    }
}

internal class FileWindowStateRepository(private val store: JsonStore<AppStateRecord>) : WindowStateRepository {
    override suspend fun windows(): Result<List<SavedWindow>> = store.read().map { saved ->
        saved.value.windows.map { SavedWindow(it.x, it.y, it.width, it.height, it.maximized, it.tabs, it.activeTab) }
    }

    override suspend fun save(windows: List<SavedWindow>): Result<Unit> {
        val records = windows.map { WindowRecord(it.x, it.y, it.width, it.height, it.maximized, it.tabs, it.activeTab) }
        return store.update { it.copy(windows = records) }.map { }
    }
}
