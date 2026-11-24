// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain.api.usecases

import com.srctool.graft.data.app.api.models.ChangelistBook
import com.srctool.graft.data.app.api.models.ChangesMode
import com.srctool.graft.data.app.api.models.ChangesPanelState
import com.srctool.graft.data.app.api.models.ChangesView
import com.srctool.graft.feature.workingcopy.domain.api.models.ChangesModel
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingCopy
import com.srctool.sinew.models.Result
import kotlinx.coroutines.flow.Flow

/** Something the panel tells the user about changelists after a refresh. */
internal sealed interface ChangelistNotice {
    /**
     * A hunk was edited past recognition next to where it was, so it left list [fromListId] for the active one
     * (Changelists, edge cases: "it moves to the active list, with a notice").
     */
    data class HunkMoved(val path: String, val fromListId: String) : ChangelistNotice
}

/** The panel's lists, and the book they came from (null in staging-area mode). */
internal data class ChangesLoad(
    val model: ChangesModel,
    val book: ChangelistBook?,
    val notices: List<ChangelistNotice>,
)

/** The Changes panel's content (Working Copy and Commit, "Changes panel"). */
internal interface LoadChangesUseCase {
    /**
     * The working copy's status in the lists [mode] asks for. In changelists mode the book is brought in line with
     * the working tree first: hunks are found again by their anchors, new changes go to the active list, and changes
     * that are gone are forgotten (`GIT-R-STAT-…`, `GIT-R-BLKS-…`, `DATA-R-…`).
     */
    suspend fun load(workingCopy: WorkingCopy, mode: ChangesMode): Result<ChangesLoad>

    /** [load] now and again each time [refreshes] emits (the session's status events, already debounced). */
    fun observe(workingCopy: WorkingCopy, mode: ChangesMode, refreshes: Flow<Unit>): Flow<Result<ChangesLoad>>
}

/** The panel's own choices in `state.json`: tree or flat, folded folders, and which changes view applies. */
internal interface PanelStateUseCase {
    suspend fun read(workingCopy: WorkingCopy): Result<ChangesPanelState>

    suspend fun setTree(workingCopy: WorkingCopy, tree: Boolean): Result<ChangesPanelState>

    /** Folds the folder at [path] if it is open, opens it if it is folded. */
    suspend fun toggleFolder(workingCopy: WorkingCopy, path: String): Result<ChangesPanelState>

    /** The mode that applies: the repository's choice, else the profile's (G3), else [app]'s. */
    suspend fun mode(workingCopy: WorkingCopy, app: ChangesMode): Result<ChangesMode>

    /** Stores the repository's choice; the same for every worktree of the repository. */
    suspend fun setView(workingCopy: WorkingCopy, view: ChangesView): Result<Unit>
}

/** Where a commit message in progress is kept: per branch in staging mode, per changelist in changelists mode. */
internal sealed interface DraftKey {
    /** [ref] is the full ref (`refs/heads/main`), or `HEAD` when detached. */
    data class Branch(val ref: String) : DraftKey

    data class Changelist(val id: String) : DraftKey
}

internal interface DraftsUseCase {
    /** Where the draft for [mode] is kept now: the current branch, or the active changelist. */
    suspend fun key(workingCopy: WorkingCopy, mode: ChangesMode): Result<DraftKey>

    /** The saved message text, or an empty string. */
    suspend fun read(workingCopy: WorkingCopy, key: DraftKey): Result<String>

    /** Saves [text]; blank text forgets the draft. */
    suspend fun write(workingCopy: WorkingCopy, key: DraftKey, text: String): Result<Unit>
}
