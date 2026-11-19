// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.presentation.search

import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.view.RepositoryViewState
import java.nio.file.Path

/** What kind of thing a result is, for its icon, its spoken label and the group it sorts into. */
enum class SearchKind(val group: SearchGroup) {
    Repository(SearchGroup.Repositories),
    Branch(SearchGroup.Refs),
    Tag(SearchGroup.Refs),
    Commit(SearchGroup.Commits),
    File(SearchGroup.Files),
    Action(SearchGroup.Actions),
}

/** The tabs of Search Everywhere after **All**, in the order their groups show. */
enum class SearchGroup { Repositories, Refs, Commits, Files, Actions }

/** What Enter does with a result; the host carries it out (S39). */
sealed interface SearchTarget {
    data class OpenRepository(val root: Path) : SearchTarget

    /** A commit, or the tip of a branch or tag: selected in the graph and shown in the details. */
    data class SelectCommit(val id: ObjectId) : SearchTarget

    /** The newest commit that changed [path], with the file marked in the details. */
    data class FileInHead(val path: String) : SearchTarget

    /** A command of the host or of a feature, by the id the host knows it as. */
    data class RunAction(val id: String) : SearchTarget
}

/**
 * One thing a source offers. [title] is what's matched and shown; [extra] (a SHA, a path) is matched too but ranks lower;
 * [detail] is shown at the right and isn't matched; [shortcut] is an action's key, as written for the OS; [recency] is
 * a time (bigger is newer) that settles ties.
 */
data class SearchCandidate(
    val key: String,
    val kind: SearchKind,
    val title: String,
    val target: SearchTarget,
    val detail: String? = null,
    val extra: String? = null,
    val shortcut: String? = null,
    val recency: Long = 0,
)

/** The repository tab a search runs in: its session (refs, HEAD) and what it shares; null on Home. */
class RepositoryScope(val session: RepositorySession, val view: RepositoryViewState)

class SearchScope(val repository: RepositoryScope? = null)

/**
 * A feature's contribution to Search Everywhere (D1), found through Koin's `getAll`. [candidates] returns what the
 * source can offer for [query] (everything it has, for a source that holds a short list; what git found, for one that
 * asks git); the host ranks, cuts and marks them. An empty [query] asks for what's worth showing before anything is
 * typed: recent repositories, actions with a shortcut.
 *
 * A failure is thrown; the host shows it for this group only.
 */
interface SearchSource {
    val group: SearchGroup

    /** True when the group needs an open repository (branches, commits, files). */
    val needsRepository: Boolean get() = false

    /** True for sources that ask git, so the host waits a moment after typing before calling them. */
    val slow: Boolean get() = false

    suspend fun candidates(query: String, scope: SearchScope): List<SearchCandidate>

    /** A line the group shows under its results when something's left out, such as a list cut at its cap. */
    fun note(): String? = null
}
