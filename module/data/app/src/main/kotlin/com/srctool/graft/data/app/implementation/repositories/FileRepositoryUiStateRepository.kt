// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.app.implementation.repositories

import com.srctool.graft.data.app.api.models.ChangesView
import com.srctool.graft.data.app.api.models.GraphAuthorKind
import com.srctool.graft.data.app.api.models.GraphBranches
import com.srctool.graft.data.app.api.models.GraphViewState
import com.srctool.graft.data.app.api.models.PanelLayout
import com.srctool.graft.data.app.api.models.RepositoryUiState
import com.srctool.graft.data.app.api.models.Saved
import com.srctool.graft.data.app.api.repositories.RepositoryUiStateRepository
import com.srctool.graft.data.app.implementation.source.local.GraphViewRecord
import com.srctool.graft.data.app.implementation.source.local.JsonStore
import com.srctool.graft.data.app.implementation.source.local.PanelsRecord
import com.srctool.graft.data.app.implementation.source.local.RepositoryStateRecord
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.map
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal class FileRepositoryUiStateRepository : RepositoryUiStateRepository {
    // One store per repository, so every worktree's tab shares its lock.
    private val stores = ConcurrentHashMap<Path, JsonStore<RepositoryStateRecord>>()

    override suspend fun read(commonDir: Path): Result<Saved<RepositoryUiState>> =
        store(commonDir).read().map { Saved(it.value.toState(), it.status) }

    override suspend fun update(
        commonDir: Path,
        change: (RepositoryUiState) -> RepositoryUiState,
    ): Result<RepositoryUiState> = store(commonDir).update { change(it.toState()).toRecord() }.map { it.toState() }

    private fun store(commonDir: Path) = stores.computeIfAbsent(commonDir.toAbsolutePath().normalize()) {
        JsonStore(
            it.resolve("graft").resolve("state.json"),
            VERSION,
            RepositoryStateRecord.serializer(),
            ::RepositoryStateRecord,
        )
    }

    private fun RepositoryStateRecord.toState() = RepositoryUiState(
        hiddenRefs.toSet(),
        soloRefs.toSet(),
        pinnedRefs.toSet(),
        graph.toState(),
        columns,
        panels.toState(),
        CHANGES_VIEWS.entries.firstOrNull { it.value == changesView }?.key ?: ChangesView.INHERIT,
    )

    private fun RepositoryUiState.toRecord() = RepositoryStateRecord(
        hiddenRefs.sorted(),
        soloRefs.sorted(),
        pinnedRefs.sorted(),
        graph.toRecord(),
        columns,
        panels.toRecord(),
        CHANGES_VIEWS.getValue(changesView),
    )

    private fun PanelsRecord.toState() =
        PanelLayout(sides.toSortedMap(), open.toSortedMap(), sizes.toSortedMap(), saved)

    private fun PanelLayout.toRecord() =
        PanelsRecord(sides.toSortedMap(), open.toSortedMap(), sizes.toSortedMap(), saved)

    private fun GraphViewRecord.toState(): GraphViewState {
        val kinds = authorTypes.mapNotNullTo(HashSet()) { name -> KINDS.entries.firstOrNull { it.value == name }?.key }
        return GraphViewState(
            branches = if (branches == "current") GraphBranches.CURRENT else GraphBranches.ALL,
            hideRemotes = hideRemotes,
            firstParent = firstParent,
            // Only unknown names (from a newer Graft) would leave nothing; show everything then.
            authorKinds = if (kinds.isEmpty() && authorTypes.isNotEmpty()) GraphAuthorKind.entries.toSet() else kinds,
            commitGraphOffered = commitGraphOffered,
        )
    }

    private fun GraphViewState.toRecord() = GraphViewRecord(
        branches = if (branches == GraphBranches.CURRENT) "current" else "all",
        hideRemotes = hideRemotes,
        firstParent = firstParent,
        authorTypes = GraphAuthorKind.entries.filter { it in authorKinds }.map { KINDS.getValue(it) },
        commitGraphOffered = commitGraphOffered,
    )

    private companion object {
        const val VERSION = 1

        val CHANGES_VIEWS =
            mapOf(
                ChangesView.INHERIT to "inherit",
                ChangesView.STAGING_AREA to "staging",
                ChangesView.CHANGELISTS to "changelists",
            )

        /** ADR-27's names for the author kinds. */
        val KINDS =
            mapOf(
                GraphAuthorKind.PERSON to "person",
                GraphAuthorKind.AI_ASSISTED to "aiAssisted",
                GraphAuthorKind.BOT to "bot",
            )
    }
}
