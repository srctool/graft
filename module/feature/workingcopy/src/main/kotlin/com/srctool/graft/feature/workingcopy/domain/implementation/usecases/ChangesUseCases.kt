// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain.implementation.usecases

import com.srctool.graft.data.app.api.models.ChangelistBook
import com.srctool.graft.data.app.api.models.ChangesMode
import com.srctool.graft.data.app.api.models.ChangesPanelState
import com.srctool.graft.data.app.api.models.ChangesView
import com.srctool.graft.data.app.api.models.CurrentFile
import com.srctool.graft.data.app.api.models.CurrentHunk
import com.srctool.graft.data.app.api.repositories.ChangelistRepository
import com.srctool.graft.data.app.api.repositories.RepositoryUiStateRepository
import com.srctool.graft.data.git.api.models.Head
import com.srctool.graft.data.git.api.models.Reanchoring
import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.data.git.api.models.StoredAnchor
import com.srctool.graft.data.git.api.repositories.BlockSource
import com.srctool.graft.data.git.api.repositories.ChangeBlocksRepository
import com.srctool.graft.data.git.api.repositories.RepositoryAccessRepository
import com.srctool.graft.data.git.api.repositories.WorkingTreeRepository
import com.srctool.graft.feature.workingcopy.domain.api.models.ChangesModels
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingCopy
import com.srctool.graft.feature.workingcopy.domain.api.models.toAnchor
import com.srctool.graft.feature.workingcopy.domain.api.models.toStored
import com.srctool.graft.feature.workingcopy.domain.api.usecases.ChangelistNotice
import com.srctool.graft.feature.workingcopy.domain.api.usecases.ChangesLoad
import com.srctool.graft.feature.workingcopy.domain.api.usecases.DraftKey
import com.srctool.graft.feature.workingcopy.domain.api.usecases.DraftsUseCase
import com.srctool.graft.feature.workingcopy.domain.api.usecases.LoadChangesUseCase
import com.srctool.graft.feature.workingcopy.domain.api.usecases.PanelStateUseCase
import com.srctool.sinew.models.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

internal class GitLoadChangesUseCase(
    private val workingTree: WorkingTreeRepository,
    private val blocks: ChangeBlocksRepository,
    private val access: RepositoryAccessRepository,
    private val changelists: ChangelistRepository,
) : LoadChangesUseCase {
    override suspend fun load(workingCopy: WorkingCopy, mode: ChangesMode): Result<ChangesLoad> {
        val tree =
            when (val r = workingTree.status(workingCopy.path)) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        if (mode == ChangesMode.STAGING_AREA) {
            return Result.Success(ChangesLoad(ChangesModels.staging(workingCopy, tree), null, emptyList()))
        }
        val gitDir =
            when (val r = access.locate(workingCopy.path)) {
                is Result.Success -> r.data.gitDir
                is Result.Failure -> return r
            }
        val current =
            when (val r = blocks.read(workingCopy.path, BlockSource.WORKTREE)) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        val read =
            when (val r = changelists.read(gitDir)) {
                is Result.Success -> r.data.value
                is Result.Failure -> return r
            }

        val stored = read.hunks.map { StoredAnchor(it.listId, it.path, it.hunk.toAnchor()) }
        val matched = Reanchoring.of(stored, current)
        val hunks = ArrayList<CurrentHunk>()
        for (kept in matched.kept) hunks += CurrentHunk(kept.path, kept.hunk.anchor.toStored(), kept.stored.tag)
        for (fresh in matched.fresh) hunks += CurrentHunk(fresh.path, fresh.hunk.anchor.toStored(), null)
        for (moved in matched.replaced) hunks += CurrentHunk(moved.path, moved.hunk.anchor.toStored(), null)
        val files = current.whole.map { CurrentFile(it.path, it.originalPath) }
        val notices = matched.replaced.map { ChangelistNotice.HunkMoved(it.path, it.stored.tag) }

        val synced = read.withHunks(hunks).withFiles(files)
        // Written only when something changed: a refresh that finds the same lists must not touch the file.
        val book =
            if (synced == read) {
                read
            } else {
                when (val r = changelists.update(gitDir) { it.withHunks(hunks).withFiles(files) }) {
                    is Result.Success -> r.data
                    is Result.Failure -> return r
                }
            }
        return Result.Success(ChangesLoad(ChangesModels.changelists(workingCopy, tree, book, current), book, notices))
    }

    override fun observe(
        workingCopy: WorkingCopy,
        mode: ChangesMode,
        refreshes: Flow<Unit>,
    ): Flow<Result<ChangesLoad>> = refreshes.onStart { emit(Unit) }.map { load(workingCopy, mode) }
}

internal class FilePanelStateUseCase(
    private val access: RepositoryAccessRepository,
    private val state: RepositoryUiStateRepository,
) : PanelStateUseCase {
    override suspend fun read(workingCopy: WorkingCopy): Result<ChangesPanelState> =
        update(workingCopy, write = false) { it }

    override suspend fun setTree(workingCopy: WorkingCopy, tree: Boolean): Result<ChangesPanelState> =
        update(workingCopy) { it.copy(tree = tree) }

    override suspend fun toggleFolder(workingCopy: WorkingCopy, path: String): Result<ChangesPanelState> =
        update(workingCopy) {
            it.copy(collapsed = if (path in it.collapsed) it.collapsed - path else it.collapsed + path)
        }

    override suspend fun mode(workingCopy: WorkingCopy, app: ChangesMode): Result<ChangesMode> {
        val common = commonDir(workingCopy)
        return when (common) {
            is Result.Failure -> common

            is Result.Success ->
                when (val r = state.read(common.data)) {
                    is Result.Success -> Result.Success(
                        ChangesView.effective(r.data.value.changesView, ChangesView.INHERIT, app),
                    )

                    is Result.Failure -> r
                }
        }
    }

    override suspend fun setView(workingCopy: WorkingCopy, view: ChangesView): Result<Unit> =
        when (val common = commonDir(workingCopy)) {
            is Result.Failure -> common

            is Result.Success ->
                when (val r = state.update(common.data) { it.copy(changesView = view) }) {
                    is Result.Success -> Result.Success(Unit)
                    is Result.Failure -> r
                }
        }

    private suspend fun update(
        workingCopy: WorkingCopy,
        write: Boolean = true,
        change: (ChangesPanelState) -> ChangesPanelState,
    ): Result<ChangesPanelState> = when (val common = commonDir(workingCopy)) {
        is Result.Failure -> common

        is Result.Success ->
            if (write) {
                when (val r = state.update(common.data) { it.copy(changes = change(it.changes)) }) {
                    is Result.Success -> Result.Success(r.data.changes)
                    is Result.Failure -> r
                }
            } else {
                when (val r = state.read(common.data)) {
                    is Result.Success -> Result.Success(r.data.value.changes)
                    is Result.Failure -> r
                }
            }
    }

    private suspend fun commonDir(workingCopy: WorkingCopy): Result<java.nio.file.Path> =
        when (val r = access.locate(workingCopy.path)) {
            is Result.Success -> Result.Success(r.data.commonDir)
            is Result.Failure -> r
        }
}

internal class FileDraftsUseCase(
    private val access: RepositoryAccessRepository,
    private val state: RepositoryUiStateRepository,
    private val changelists: ChangelistRepository,
) : DraftsUseCase {
    override suspend fun key(workingCopy: WorkingCopy, mode: ChangesMode): Result<DraftKey> = when (mode) {
        ChangesMode.STAGING_AREA ->
            when (val r = access.summary(workingCopy.path)) {
                is Result.Success ->
                    Result.Success(
                        DraftKey.Branch(
                            when (val head = r.data.head) {
                                is Head.Branch -> "refs/heads/${head.name}"
                                is Head.Detached -> "HEAD"
                            },
                        ),
                    )

                is Result.Failure -> r
            }

        ChangesMode.CHANGELISTS ->
            when (val located = location(workingCopy)) {
                is Result.Failure -> located

                is Result.Success ->
                    when (val r = changelists.read(located.data.gitDir)) {
                        is Result.Success -> Result.Success(DraftKey.Changelist(r.data.value.activeId))
                        is Result.Failure -> r
                    }
            }
    }

    override suspend fun read(workingCopy: WorkingCopy, key: DraftKey): Result<String> =
        when (val located = location(workingCopy)) {
            is Result.Failure -> located

            is Result.Success ->
                when (key) {
                    is DraftKey.Branch ->
                        when (val r = state.read(located.data.commonDir)) {
                            is Result.Success -> Result.Success(r.data.value.changes.drafts[key.ref].orEmpty())
                            is Result.Failure -> r
                        }

                    is DraftKey.Changelist ->
                        when (val r = changelists.read(located.data.gitDir)) {
                            is Result.Success -> Result.Success(r.data.value.find(key.id)?.draft.orEmpty())
                            is Result.Failure -> r
                        }
                }
        }

    override suspend fun write(workingCopy: WorkingCopy, key: DraftKey, text: String): Result<Unit> =
        when (val located = location(workingCopy)) {
            is Result.Failure -> located

            is Result.Success ->
                when (key) {
                    is DraftKey.Branch ->
                        when (
                            val r =
                                state.update(located.data.commonDir) {
                                    val drafts = it.changes.drafts
                                    it.copy(
                                        changes =
                                        it.changes.copy(
                                            drafts = if (text.isBlank()) {
                                                drafts - key.ref
                                            } else {
                                                drafts +
                                                    (key.ref to text)
                                            },
                                        ),
                                    )
                                }
                        ) {
                            is Result.Success -> Result.Success(Unit)
                            is Result.Failure -> r
                        }

                    is DraftKey.Changelist ->
                        when (
                            val r =
                                changelists.update(located.data.gitDir) { book: ChangelistBook ->
                                    // A list deleted since is nothing to keep a draft for.
                                    if (book.find(key.id) ==
                                        null
                                    ) {
                                        book
                                    } else {
                                        book.withDraft(key.id, if (text.isBlank()) "" else text)
                                    }
                                }
                        ) {
                            is Result.Success -> Result.Success(Unit)
                            is Result.Failure -> r
                        }
                }
        }

    private suspend fun location(workingCopy: WorkingCopy): Result<RepositoryLocation> = access.locate(workingCopy.path)
}
