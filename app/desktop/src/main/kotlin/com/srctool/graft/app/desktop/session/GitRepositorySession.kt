// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.app.desktop.session

import com.srctool.graft.core.git.WriteQueue
import com.srctool.graft.core.watcher.FileWatcher
import com.srctool.graft.core.watcher.RepoChange
import com.srctool.graft.core.watcher.WatchedRepository
import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.data.git.api.repositories.RefRepository
import com.srctool.graft.data.git.api.repositories.RepositoryAccessRepository
import com.srctool.graft.presentation.session.RepoSnapshot
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.session.SnapshotPart
import com.srctool.sinew.models.Result
import com.srctool.sinew.presentation.AppService
import com.srctool.sinew.presentation.ServiceLifetime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One open repository (Architecture, "Repository session"): its snapshot, kept current by the file watcher, and its
 * write queue. It runs while [listen]'s scope lives; cancelling that scope stops the watcher and every git process
 * the session started.
 *
 * Each part refreshes in its own loop over a conflated channel, so a burst of changes while a read runs queues exactly
 * one more read, never one per change. Status is debounced by the watcher already (300 ms).
 */
internal class GitRepositorySession(
    override val location: RepositoryLocation,
    private val refs: RefRepository,
    private val access: RepositoryAccessRepository,
    private val watcher: FileWatcher,
    private val writes: WriteQueue,
) : AppService(ServiceLifetime.Session),
    RepositorySession {
    private val state = MutableStateFlow(RepoSnapshot(location))
    override val snapshot: StateFlow<RepoSnapshot> = state.asStateFlow()

    private val changes =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val fileChanges: SharedFlow<Unit> = changes.asSharedFlow()

    private val requests = SnapshotPart.entries.associateWith { Channel<Unit>(Channel.CONFLATED) }

    /** The folder git runs in: the working tree, or a bare repository's git folder. */
    private val root = location.workTree ?: location.gitDir

    override fun refresh(parts: Set<SnapshotPart>) {
        parts.forEach { requests.getValue(it).trySend(Unit) }
    }

    override suspend fun <T> write(block: suspend () -> T): T = writes.withWrite(location.commonDir.toString(), block)

    override fun listen(scope: CoroutineScope) {
        scope.launch { for (ignored in requests.getValue(SnapshotPart.REFS)) readRefs() }
        scope.launch { for (ignored in requests.getValue(SnapshotPart.STATUS)) readStatus() }
        refresh()
        scope.launch {
            watcher.watch(WatchedRepository(location.workTree, location.gitDir, location.commonDir)).collect(::onChange)
        }
    }

    private fun onChange(change: RepoChange) {
        // A ref or an operation moving is not a file changing; a full refresh may hide one, so it counts.
        if (change == RepoChange.StatusChanged || change is RepoChange.FullRefresh) changes.tryEmit(Unit)
        when (change) {
            // A ref move can change HEAD and its upstream's ahead/behind too.
            RepoChange.RefsChanged -> refresh()

            RepoChange.StatusChanged -> refresh(setOf(SnapshotPart.STATUS))

            // G1 has no operation banner; a merge or rebase starting or ending still moves HEAD and the index.
            RepoChange.OperationChanged -> refresh(setOf(SnapshotPart.STATUS))

            is RepoChange.FullRefresh -> refresh()

            is RepoChange.PollingFallback -> {
                state.update { it.copy(polling = true) }
                refresh()
            }
        }
    }

    private suspend fun readRefs() = read(SnapshotPart.REFS, { refs.refs(root) }) { s, refs -> s.copy(refs = refs) }

    private suspend fun readStatus() {
        // A bare repository has no working tree to report on.
        if (location.isBare) {
            state.update { it.copy(loaded = it.loaded + SnapshotPart.STATUS) }
            return
        }
        read(SnapshotPart.STATUS, { access.summary(root) }) { s, summary -> s.copy(summary = summary) }
    }

    private suspend fun <T> read(
        part: SnapshotPart,
        load: suspend () -> Result<T>,
        apply: (RepoSnapshot, T) -> RepoSnapshot,
    ) {
        state.update { it.copy(refreshing = it.refreshing + part) }
        try {
            when (val r = load()) {
                is Result.Success ->
                    state.update { apply(it, r.data).copy(loaded = it.loaded + part, failures = it.failures - part) }

                is Result.Failure -> state.update { it.copy(failures = it.failures + (part to r.error)) }
            }
        } finally {
            state.update { it.copy(refreshing = it.refreshing - part) }
        }
    }
}
