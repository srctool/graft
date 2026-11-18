// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.presentation.home

import com.srctool.graft.data.app.api.models.KnownRepository
import com.srctool.graft.data.app.api.models.Workspace
import com.srctool.graft.data.git.api.models.RepositorySummary
import com.srctool.graft.feature.home.domain.api.models.OpenOutcome
import com.srctool.graft.feature.home.domain.api.models.ScannedRepository
import java.nio.file.Path

/** What Home lists on the right: every known repository, or one workspace's. */
internal sealed interface HomeView {
    data object All : HomeView

    data class Workspace(val id: String) : HomeView
}

/** A repository's summary, read as its row comes into view. */
internal sealed interface SummaryState {
    data object Loading : SummaryState

    data class Loaded(val summary: RepositorySummary) : SummaryState

    /** The repository can't be read (a bare one has no status; a folder that's gone). */
    data class Unavailable(val error: Throwable) : SummaryState
}

/** One row of the list: a known repository, or a workspace member (which may not be known). */
internal data class HomeRow(val path: Path, val known: KnownRepository?) {
    val key: String get() = path.toString()
    val name: String get() = path.fileName?.toString() ?: path.toString()
    val pinned: Boolean get() = known?.pinned == true
    val missing: Boolean get() = known?.missing == true
}

/** A dialog over Home (the wireframe's S1, S2 and S3 and the small ones between them). */
internal sealed interface HomeDialog {
    data object None : HomeDialog

    /** S2, optionally into [folder] (a folder the user opened that wasn't a repository). */
    data class Clone(val folder: Path? = null) : HomeDialog

    /** S3, optionally for [folder]. */
    data class Init(val folder: Path? = null) : HomeDialog

    /** A folder that isn't a repository: offer **Init here** or **Clone here**. */
    data class NotARepository(val folder: Path) : HomeDialog

    /** "Dubious ownership": the exact change **Trust this folder** makes, shown before it's made. */
    data class Trust(val outcome: OpenOutcome.Untrusted, val folder: Path) : HomeDialog

    data class NewWorkspace(val name: String = "") : HomeDialog

    data class RenameWorkspace(val id: String, val name: String) : HomeDialog

    data class DeleteWorkspace(val id: String, val name: String) : HomeDialog

    /** Removing a repository from Home's list; the folder stays. */
    data class RemoveRepository(val path: Path) : HomeDialog

    /** Scanning [folder]; [found] fills in when it finishes. */
    data class Scan(
        val folder: Path,
        val scanning: Boolean = true,
        val found: List<ScannedRepository> = emptyList(),
        val selected: Set<Path> = emptySet(),
        val workspaceName: String = "",
    ) : HomeDialog

    /** Known repositories to add to workspace [id]. */
    data class AddToWorkspace(val id: String, val selected: Set<Path> = emptySet()) : HomeDialog

    /** Anything that went wrong with its code and, for git, **Show output**. */
    data class Problem(val error: Throwable?, val text: String? = null, val outputShown: Boolean = false) : HomeDialog
}

internal data class HomeState(
    val phase: Phase = Phase.Loading,
    val error: Throwable? = null,
    val repositories: List<KnownRepository> = emptyList(),
    /** The saved list is from a newer Graft: shown, never written back. */
    val readOnly: Boolean = false,
    val workspaces: List<Workspace> = emptyList(),
    val view: HomeView = HomeView.All,
    val summaries: Map<Path, SummaryState> = emptyMap(),
    val query: String = "",
    /** The focused row's key (its path). */
    val focus: String? = null,
    val dialog: HomeDialog = HomeDialog.None,
    /** Epoch seconds, for "2 h ago". */
    val now: Long = 0,
) {
    enum class Phase { Loading, Ready, Failed }

    val workspace: Workspace? get() = (view as? HomeView.Workspace)?.let { v ->
        workspaces.firstOrNull { it.id == v.id }
    }

    /** The rows the right side shows: pinned first, then by last opened; a workspace's in its own order; filtered. */
    val rows: List<HomeRow>
        get() {
            val known = repositories.associateBy { it.path }
            val all =
                when (val v = view) {
                    HomeView.All -> repositories.sortedWith(
                        compareByDescending<KnownRepository> {
                            it.pinned
                        }.thenByDescending { it.lastOpened },
                    ).map { HomeRow(it.path, it) }

                    is HomeView.Workspace -> workspace?.repositories.orEmpty().map { HomeRow(it, known[it]) }
                }
            return if (query.isBlank()) all else all.filter { matches(it, query) }
        }

    val focusIndex: Int get() = rows.indexOfFirst { it.key == focus }
}

/** A row matches a search by its name or its path, ignoring case. */
internal fun matches(row: HomeRow, query: String): Boolean {
    val q = query.trim()
    return row.name.contains(q, ignoreCase = true) || row.path.toString().contains(q, ignoreCase = true)
}
