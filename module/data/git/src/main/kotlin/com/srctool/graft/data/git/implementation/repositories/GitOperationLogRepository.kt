// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.repositories

import com.srctool.graft.core.git.GitExitException
import com.srctool.graft.core.git.GitNotFoundException
import com.srctool.graft.core.git.GitUndoRefusedException
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.OperationEntry
import com.srctool.graft.data.git.api.models.OperationKind
import com.srctool.graft.data.git.api.models.UndoState
import com.srctool.graft.data.git.api.repositories.OperationLogRepository
import com.srctool.graft.data.git.implementation.source.local.MoveRefCommand
import com.srctool.graft.data.git.implementation.source.local.OpLogStore
import com.srctool.graft.data.git.implementation.source.local.UndoReplay
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path

internal class GitOperationLogRepository(private val recorder: MutationRecorder, private val store: OpLogStore) :
    OperationLogRepository {
    override suspend fun entries(repository: Path, limit: Int): Result<List<OperationEntry>> =
        readLog(repository).map { it.asReversed().take(limit) }

    override suspend fun undoState(repository: Path): Result<UndoState> = readLog(repository).map(UndoReplay::replay)

    override suspend fun undo(repository: Path): Result<OperationEntry> {
        val target = undoState(repository).orReturn { return it }.undo
            ?: return refused("there is nothing to undo")
        // The change must still be where the operation left it: anything else would drop commits made since.
        checkWhere(repository, target, expectedTip = target.after.head)?.let { return it }
        return move(repository, target, to = target.before.head, expected = target.after.head, OperationKind.UNDO)
    }

    override suspend fun redo(repository: Path): Result<OperationEntry> {
        val target = undoState(repository).orReturn { return it }.redo
            ?: return refused("there is nothing to redo")
        checkWhere(repository, target, expectedTip = target.before.head)?.let { return it }
        return move(repository, target, to = target.after.head, expected = target.before.head, OperationKind.REDO)
    }

    private suspend fun move(
        repository: Path,
        target: OperationEntry,
        to: ObjectId?,
        expected: ObjectId?,
        kind: OperationKind,
    ): Result<OperationEntry> {
        val ref = target.before.branch ?: "HEAD"
        val verb = if (kind == OperationKind.UNDO) "undo" else "redo"
        val what = target.kind.name.lowercase()
        val moved = recorder.run(MoveRefCommand(ref, to, expected, "graft: $verb $what"), repository, kind)
        return moved.map { target }
    }

    /** Null when HEAD is on the operation's branch at [expectedTip]; otherwise the refusal, in words. */
    private suspend fun checkWhere(repository: Path, target: OperationEntry, expectedTip: ObjectId?): Result<Nothing>? {
        val now = recorder.refs(repository)
        val branchName = target.before.branch?.removePrefix("refs/heads/")
        return when {
            now.branch != target.before.branch ->
                (
                    if (target.before.branch == null) {
                        "HEAD is on a branch now, and was detached"
                    } else {
                        "HEAD is not on $branchName now"
                    }
                    ).let { reason ->
                    Result.Failure(GitUndoRefusedException(reason))
                }

            now.head != expectedTip ->
                Result.Failure(
                    GitUndoRefusedException(
                        "${branchName ?: "HEAD"} has moved since, so going back would lose commits",
                    ),
                )

            else -> null
        }
    }

    private fun notARepository(): Result.Failure =
        Result.Failure(GitNotFoundException("OLOG", listOf("rev-parse"), 128, "fatal: not a git repository"))

    private fun refused(reason: String): Result<Nothing> = Result.Failure(GitUndoRefusedException(reason))

    private suspend fun readLog(repository: Path): Result<List<OperationEntry>> {
        val where = recorder.location(repository)
            ?: return notARepository()
        return try {
            Result.Success(withContext(Dispatchers.IO) { store.read(where.commonDir) })
        } catch (e: IOException) {
            Result.Failure(GitExitException("OLOG", listOf("read", "oplog.jsonl"), null, e.message.orEmpty()))
        }
    }
}
