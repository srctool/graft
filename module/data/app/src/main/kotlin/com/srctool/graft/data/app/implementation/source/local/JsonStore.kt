// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.app.implementation.source.local

import com.srctool.graft.core.preference.Stored
import com.srctool.graft.core.preference.StoredOrigin
import com.srctool.graft.core.preference.VersionedJsonFile
import com.srctool.graft.data.app.api.models.Saved
import com.srctool.graft.data.app.api.models.SavedStatus
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.nio.file.Path

/**
 * One versioned JSON file with read-modify-write updates: every change re-reads the file first (Storage Formats 5:
 * "rewritten only after re-reading"), and changes from this process are applied one at a time.
 */
internal class JsonStore<T>(path: Path, version: Int, serializer: KSerializer<T>, defaults: () -> T) {
    private val file = VersionedJsonFile(path, version, serializer, defaults)
    private val mutex = Mutex()

    suspend fun read(): Result<Saved<T>> = file.read().map { it.toSaved() }

    /** Applies [change] to the stored value and writes it; the new value is returned. */
    suspend fun update(change: (T) -> T): Result<T> = mutex.withLock {
        val current =
            when (val r = file.read()) {
                is Result.Success -> r.data.value
                is Result.Failure -> return r
            }
        val next = change(current)
        if (next == current) return Result.Success(current)
        file.write(next).map { next }
    }
}

internal fun <T> Stored<T>.toSaved(): Saved<T> = Saved(
    value,
    when (val o = origin) {
        StoredOrigin.Missing -> SavedStatus.Missing
        StoredOrigin.Current -> SavedStatus.Current
        is StoredOrigin.Newer -> SavedStatus.Newer(o.version)
        is StoredOrigin.Recovered -> SavedStatus.Recovered(o.corruptCopy)
    },
)

/** `app-state.json` v1: what Graft remembers between runs that isn't a setting. */
@Serializable
internal data class AppStateRecord(
    val repositories: List<RepositoryRecord> = emptyList(),
    val windows: List<WindowRecord> = emptyList(),
)

@Serializable
internal data class RepositoryRecord(val path: String, val lastOpened: Long, val pinned: Boolean = false)

@Serializable
internal data class WindowRecord(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val maximized: Boolean = false,
)

/** `workspace.json` v1. */
@Serializable
internal data class WorkspacesRecord(val workspaces: List<WorkspaceRecord> = emptyList())

@Serializable
internal data class WorkspaceRecord(val id: String, val name: String, val repositories: List<String> = emptyList())

/** `$GIT_COMMON_DIR/graft/state.json` v1; lists are kept sorted so the file only changes when the state does. */
@Serializable
internal data class RepositoryStateRecord(
    val hiddenRefs: List<String> = emptyList(),
    val soloRefs: List<String> = emptyList(),
    val pinnedRefs: List<String> = emptyList(),
    val graph: GraphViewRecord = GraphViewRecord(),
    val columns: Map<String, Float?> = emptyMap(),
)

/** ADR-27's `graph` object. Values are strings so an unknown one from a newer Graft reads as the default. */
@Serializable
internal data class GraphViewRecord(
    val branches: String = "all",
    val hideRemotes: Boolean = false,
    val firstParent: Boolean = false,
    val authorTypes: List<String> = listOf("person", "aiAssisted", "bot"),
    val commitGraphOffered: Boolean = false,
)
