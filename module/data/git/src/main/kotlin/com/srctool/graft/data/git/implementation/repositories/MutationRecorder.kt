// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.repositories

import com.srctool.graft.core.exception.Redaction
import com.srctool.graft.core.git.GitCommand
import com.srctool.graft.core.git.GitException
import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.core.git.WriteQueue
import com.srctool.graft.data.git.api.models.OperationEntry
import com.srctool.graft.data.git.api.models.OperationKind
import com.srctool.graft.data.git.api.models.OperationOutcome
import com.srctool.graft.data.git.api.models.RefState
import com.srctool.graft.data.git.implementation.source.local.HeadBranchCommand
import com.srctool.graft.data.git.implementation.source.local.HistoryLocation
import com.srctool.graft.data.git.implementation.source.local.LocateHistoryCommand
import com.srctool.graft.data.git.implementation.source.local.OpLogStore
import com.srctool.graft.data.git.implementation.source.local.ResolveCommand
import com.srctool.sinew.models.Result
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs a mutating command and writes its op log entry: the refs are read just before and just after, and the entry is
 * written however the command ends (done, failed or cancelled). Recorded operations of one repository run one at a
 * time on this recorder's own queue, so the "before" and "after" are not another recorded operation's.
 *
 * Writing the entry never fails the command: a log that can't be written is a missing entry, not a lost commit.
 */
internal class MutationRecorder(
    private val runner: GitRunner,
    private val store: OpLogStore = OpLogStore(),
    /** Milliseconds since 1970; a test passes its own clock. */
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val queue = WriteQueue()
    private val locations = ConcurrentHashMap<Path, HistoryLocation>()

    suspend fun <T> run(
        cmd: GitCommand<T>,
        repository: Path,
        kind: OperationKind = OperationKind.OTHER,
        input: ByteArray? = null,
    ): Result<T> {
        // Not a repository (or git can't start): there is nowhere to log, and the command reports its own failure.
        val where = locate(repository) ?: return runner.run(cmd, repository, input)
        return queue.withWrite(where.commonDir.toString()) {
            val before = refs(repository)
            val started = now()
            val result =
                try {
                    runner.run(cmd, repository, input)
                } catch (e: CancellationException) {
                    record(where, repository, cmd, kind, started, before, OperationOutcome.CANCELLED, null, "")
                    throw e
                }
            when (result) {
                is Result.Success ->
                    record(where, repository, cmd, kind, started, before, OperationOutcome.SUCCEEDED, 0, "")

                is Result.Failure -> {
                    val git = result.error as? GitException
                    record(
                        where, repository, cmd, kind, started, before, OperationOutcome.FAILED,
                        git?.exitCode, git?.stderr.orEmpty().takeLast(STDERR_TAIL),
                    )
                }
            }
            result
        }
    }

    /** The refs now. A ref that can't be read is recorded as absent: the entry is still worth having. */
    suspend fun refs(repository: Path): RefState {
        val branch = (runner.run(HeadBranchCommand(), repository) as? Result.Success)?.data
        val head = (runner.run(ResolveCommand("HEAD"), repository) as? Result.Success)?.data
        val stash = (runner.run(ResolveCommand("refs/stash"), repository) as? Result.Success)?.data
        return RefState(head, branch, stash)
    }

    suspend fun location(repository: Path): HistoryLocation? = locate(repository)

    private suspend fun locate(repository: Path): HistoryLocation? {
        val key = repository.toAbsolutePath().normalize()
        locations[key]?.let { return it }
        val found = (runner.run(LocateHistoryCommand("OLOC"), repository) as? Result.Success)?.data ?: return null
        locations[key] = found
        return found
    }

    private suspend fun record(
        where: HistoryLocation,
        repository: Path,
        cmd: GitCommand<*>,
        kind: OperationKind,
        started: Long,
        before: RefState,
        outcome: OperationOutcome,
        exitCode: Int?,
        stderrTail: String,
    ) = withContext(NonCancellable) {
        val entry =
            OperationEntry(
                time = started,
                worktree = where.worktreeId,
                kind = kind,
                argv = (listOf("git") + cmd.args).map(Redaction::redact),
                exitCode = exitCode,
                outcome = outcome,
                stderrTail = Redaction.redact(stderrTail),
                before = before,
                after = refs(repository),
            )
        try {
            store.append(where.commonDir, entry)
        } catch (_: IOException) {
            // See the class comment.
        }
    }

    private companion object {
        const val STDERR_TAIL = 2048
    }
}
