// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.app.implementation.repositories

import com.srctool.graft.data.app.api.models.Changelist
import com.srctool.graft.data.app.api.models.ChangelistBook
import com.srctool.graft.data.app.api.models.FileAssignment
import com.srctool.graft.data.app.api.models.HunkAssignment
import com.srctool.graft.data.app.api.models.Saved
import com.srctool.graft.data.app.api.models.StoredHunk
import com.srctool.graft.data.app.api.repositories.ChangelistRepository
import com.srctool.graft.data.app.implementation.source.local.ChangelistRecord
import com.srctool.graft.data.app.implementation.source.local.ChangelistsRecord
import com.srctool.graft.data.app.implementation.source.local.FileAssignmentRecord
import com.srctool.graft.data.app.implementation.source.local.HunkAssignmentRecord
import com.srctool.graft.data.app.implementation.source.local.JsonStore
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.map
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal class FileChangelistRepository : ChangelistRepository {
    private val stores = ConcurrentHashMap<Path, JsonStore<ChangelistsRecord>>()

    override suspend fun read(gitDir: Path): Result<Saved<ChangelistBook>> =
        store(gitDir).read().map { Saved(it.value.toBook(), it.status) }

    override suspend fun update(gitDir: Path, change: (ChangelistBook) -> ChangelistBook): Result<ChangelistBook> =
        store(gitDir).update { change(it.toBook()).toRecord() }.map { it.toBook() }

    private fun store(gitDir: Path) = stores.computeIfAbsent(gitDir.toAbsolutePath().normalize()) {
        JsonStore(
            it.resolve("graft").resolve("changelists.json"),
            VERSION,
            ChangelistsRecord.serializer(),
            ::ChangelistsRecord,
        )
    }

    /** A file that lost its Default list, or names an active list that is gone, still reads as a working book. */
    private fun ChangelistsRecord.toBook(): ChangelistBook {
        val read = lists.map { Changelist(it.id, it.name, it.description, it.draft) }
        val all = if (read.any { it.id == ChangelistBook.DEFAULT_ID }) read else listOf(ChangelistBook.DEFAULT) + read
        val known = all.map { it.id }.toSet()
        val fallback = ChangelistBook.DEFAULT_ID
        return ChangelistBook(
            lists = all,
            activeId = active.takeIf { it in known } ?: fallback,
            files = files.map { FileAssignment(it.path, it.list.takeIf { id -> id in known } ?: fallback) },
            hunks =
            hunks.map {
                HunkAssignment(
                    it.path,
                    it.list.takeIf { id -> id in known } ?: fallback,
                    StoredHunk(it.oldStart, it.oldCount, it.change, it.removed, it.base, it.removedCount),
                )
            },
        )
    }

    private fun ChangelistBook.toRecord() = ChangelistsRecord(
        lists = lists.map { ChangelistRecord(it.id, it.name, it.description, it.draft) },
        active = activeId,
        files = files.map { FileAssignmentRecord(it.path, it.listId) },
        hunks =
        hunks.map {
            HunkAssignmentRecord(
                it.path,
                it.listId,
                it.hunk.oldStart,
                it.hunk.oldCount,
                it.hunk.change,
                it.hunk.removed,
                it.hunk.base,
                it.hunk.removedCount,
            )
        },
    )

    private companion object {
        const val VERSION = 1
    }
}
