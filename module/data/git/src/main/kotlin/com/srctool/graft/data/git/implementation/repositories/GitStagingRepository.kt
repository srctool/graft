// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.repositories

import com.srctool.graft.core.git.GitCommand
import com.srctool.graft.core.git.GitExitException
import com.srctool.graft.core.git.GitPatchException
import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.core.git.PatchProblem
import com.srctool.graft.data.git.api.models.ChangeSelection
import com.srctool.graft.data.git.api.models.FileSelection
import com.srctool.graft.data.git.api.models.FileSelection.Scope
import com.srctool.graft.data.git.api.repositories.LocalHistoryRepository
import com.srctool.graft.data.git.api.repositories.StagingRepository
import com.srctool.graft.data.git.implementation.source.local.AddCommand
import com.srctool.graft.data.git.implementation.source.local.ApplyPatchCommand
import com.srctool.graft.data.git.implementation.source.local.ApplyTo
import com.srctool.graft.data.git.implementation.source.local.InsideRepositoryCommand
import com.srctool.graft.data.git.implementation.source.local.NewFileDiffCommand
import com.srctool.graft.data.git.implementation.source.local.PatchBuilder
import com.srctool.graft.data.git.implementation.source.local.RawFilePatch
import com.srctool.graft.data.git.implementation.source.local.RawPatchParser
import com.srctool.graft.data.git.implementation.source.local.ResetPathsCommand
import com.srctool.graft.data.git.implementation.source.local.RestoreWorktreeCommand
import com.srctool.graft.data.git.implementation.source.local.SelectionOutOfRange
import com.srctool.graft.data.git.implementation.source.local.StagingDiffCommand
import com.srctool.graft.data.git.implementation.source.local.TrackedPathsCommand
import com.srctool.graft.data.git.implementation.source.local.pathspecInput
import com.srctool.sinew.models.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal class GitStagingRepository(
    private val runner: GitRunner,
    private val history: LocalHistoryRepository,
    private val recorder: MutationRecorder,
) : StagingRepository {
    override suspend fun stage(repository: Path, selection: ChangeSelection, contextLines: Int): Result<Unit> =
        move(repository, selection, contextLines, Direction.STAGE)

    override suspend fun unstage(repository: Path, selection: ChangeSelection, contextLines: Int): Result<Unit> =
        move(repository, selection, contextLines, Direction.UNSTAGE)

    override suspend fun discard(repository: Path, selection: ChangeSelection, contextLines: Int): Result<Unit> =
        move(repository, selection, contextLines, Direction.DISCARD)

    private enum class Direction(val diffCached: Boolean, val toIndex: Boolean, val applyTo: ApplyTo) {
        /** Index ← working tree: the patch meets the diff's old side, the index. */
        STAGE(diffCached = false, toIndex = true, applyTo = ApplyTo.OLD),

        /** Index → `HEAD`: the diff is `HEAD` against the index, applied backwards to the index. */
        UNSTAGE(diffCached = true, toIndex = true, applyTo = ApplyTo.NEW),

        /** Working tree → index: the diff is index against files, applied backwards to the files. */
        DISCARD(diffCached = false, toIndex = false, applyTo = ApplyTo.NEW),
    }

    /**
     * Puts the selection into a scratch index (a [com.srctool.graft.core.git.TempIndex]'s environment) instead of the
     * repository's, for a commit of a selection. Nothing is recorded: the scratch index is not the repository's state.
     */
    internal suspend fun stageInto(
        repository: Path,
        selection: ChangeSelection,
        contextLines: Int,
        index: Map<String, String?>,
    ): Result<Unit> = move(repository, selection, contextLines, Direction.STAGE, index) { command, input ->
        runner.run(command, repository, input)
    }

    private suspend fun move(
        repository: Path,
        selection: ChangeSelection,
        contextLines: Int,
        direction: Direction,
        index: Map<String, String?> = emptyMap(),
        write: suspend (GitCommand<Unit>, ByteArray) -> Result<Unit> = { command, input ->
            recorder.run(command, repository, input = input)
        },
    ): Result<Unit> {
        val files = selection.files
        if (files.isEmpty()) return Result.Success(Unit)
        // Without this `git diff` outside a repository would quietly compare two files on disk.
        (runner.run(InsideRepositoryCommand(), repository) as? Result.Failure)?.let { return it }

        val whole = files.filter { it.scope == Scope.WholeFile }
        val partial = files.filter { it.scope != Scope.WholeFile }
        val tracked =
            if (partial.isEmpty() && direction != Direction.DISCARD) {
                emptySet()
            } else {
                when (val r = runner.run(TrackedPathsCommand(files.map { it.path }, index), repository)) {
                    is Result.Success -> r.data
                    is Result.Failure -> return r
                }
            }

        // Every patch is built before anything is written, so a stale selection changes nothing.
        val patch = java.io.ByteArrayOutputStream()
        for (file in partial) {
            val built =
                when (val r = patchFor(repository, file, direction, tracked, contextLines, index)) {
                    is Result.Success -> r.data
                    is Result.Failure -> return r
                }
            built?.let(patch::write)
        }
        // The way back, taken once the selection is known to fit and before anything is thrown away (E2). If it
        // can't be taken, nothing is discarded.
        if (direction == Direction.DISCARD) {
            (history.snapshot(repository, "before discard") as? Result.Failure)?.let { return it }
        }
        if (patch.size() > 0) {
            val command =
                ApplyPatchCommand(cached = direction.toIndex, reverse = direction.applyTo == ApplyTo.NEW, index = index)
            val applied = write(command, patch.toByteArray())
            if (applied is Result.Failure) return applied
        }
        if (whole.isEmpty()) return Result.Success(Unit)
        return when (direction) {
            Direction.STAGE -> write(AddCommand(index), paths(whole))
            Direction.UNSTAGE -> write(ResetPathsCommand(), paths(whole))
            Direction.DISCARD -> discardWhole(repository, whole, tracked)
        }
    }

    /** The files' own path and, for a rename, the path it came from. */
    private fun paths(files: List<FileSelection>): ByteArray =
        pathspecInput(files.flatMap { listOfNotNull(it.path, it.originalPath) })

    private suspend fun discardWhole(repository: Path, files: List<FileSelection>, tracked: Set<String>): Result<Unit> {
        val (known, untracked) = files.partition { it.path in tracked }
        if (known.isNotEmpty()) {
            val restored = recorder.run(RestoreWorktreeCommand(), repository, input = paths(known))
            if (restored is Result.Failure) return restored
        }
        return deleteUntracked(repository, untracked.map { it.path })
    }

    /** An untracked file has no other copy, so discarding it is deleting it (a link is removed, never followed). */
    private suspend fun deleteUntracked(repository: Path, paths: List<String>): Result<Unit> {
        if (paths.isEmpty()) return Result.Success(Unit)
        val root = repository.toAbsolutePath().normalize()
        return withContext(Dispatchers.IO) {
            try {
                paths.forEach { path ->
                    val target = root.resolve(path).normalize()
                    // Only files inside the working tree, and never the repository's own folder.
                    val inside = target.startsWith(root) && target != root && !target.startsWith(root.resolve(".git"))
                    if (!inside) throw IOException("$path is outside the working tree")
                    Files.deleteIfExists(target)
                }
                Result.Success(Unit)
            } catch (e: IOException) {
                Result.Failure(GitExitException("DEL", listOf("delete"), null, e.message.orEmpty()))
            }
        }
    }

    /** The patch for one partly selected file, or null when it selects no change. */
    private suspend fun patchFor(
        repository: Path,
        file: FileSelection,
        direction: Direction,
        tracked: Set<String>,
        contextLines: Int,
        index: Map<String, String?>,
    ): Result<ByteArray?> {
        val isNew = file.path !in tracked
        if (isNew && direction != Direction.STAGE) {
            return Result.Failure(refused("only a whole untracked file can be ${verb(direction)}"))
        }
        val diff: GitCommand<ByteArray> =
            if (isNew) {
                NewFileDiffCommand(file.path, contextLines)
            } else {
                StagingDiffCommand(
                    direction.diffCached,
                    listOfNotNull(file.path, file.originalPath),
                    contextLines,
                    index,
                )
            }
        val bytes =
            when (val r = runner.run(diff, repository)) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        // Without rename detection a renamed file is a deletion and an addition: take the one that isn't the old path.
        val sections = RawPatchParser.parse(bytes)
        val section =
            sections.firstOrNull { it.kind != RawFilePatch.Kind.DELETED || sections.size == 1 }
                ?: return Result.Failure(refused("${file.path} has no changes"))
        return try {
            Result.Success(PatchBuilder.build(file.path, section, file.scope, direction.applyTo))
        } catch (e: SelectionOutOfRange) {
            Result.Failure(refused(e.message.orEmpty()))
        }
    }

    private fun verb(direction: Direction) = if (direction == Direction.DISCARD) "discarded" else "unstaged"

    private fun refused(reason: String): GitPatchException =
        GitPatchException("APLY", listOf("apply"), null, "error: $reason", PatchProblem.DOES_NOT_APPLY)
}
