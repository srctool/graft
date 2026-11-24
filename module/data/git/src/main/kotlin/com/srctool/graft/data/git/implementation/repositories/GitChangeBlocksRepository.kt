// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.repositories

import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.data.git.api.models.ChangeEntry
import com.srctool.graft.data.git.api.models.ChangeStatus
import com.srctool.graft.data.git.api.models.FileBlocks
import com.srctool.graft.data.git.api.models.WholeChange
import com.srctool.graft.data.git.api.models.WorkingBlocks
import com.srctool.graft.data.git.api.repositories.BlockSource
import com.srctool.graft.data.git.api.repositories.ChangeBlocksRepository
import com.srctool.graft.data.git.implementation.source.local.BlockDiffCommand
import com.srctool.graft.data.git.implementation.source.local.HunkAnchors
import com.srctool.graft.data.git.implementation.source.local.InsideRepositoryCommand
import com.srctool.graft.data.git.implementation.source.local.RawPatchParser
import com.srctool.graft.data.git.implementation.source.local.ResolveCommand
import com.srctool.graft.data.git.implementation.source.local.WorkingTreeCommand
import com.srctool.sinew.models.Result
import java.nio.file.Path

internal class GitChangeBlocksRepository(private val runner: GitRunner) : ChangeBlocksRepository {
    override suspend fun read(repository: Path, source: BlockSource, contextLines: Int): Result<WorkingBlocks> {
        (runner.run(InsideRepositoryCommand(), repository) as? Result.Failure)?.let { return it }
        val tree =
            when (val r = runner.run(WorkingTreeCommand(includeIgnored = false), repository)) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        val unborn = (runner.run(ResolveCommand("HEAD"), repository) as? Result.Success)?.data == null
        val cached = source == BlockSource.INDEX
        val files = ArrayList<FileBlocks>()
        val whole = ArrayList<WholeChange>()
        for (entry in tree.entries) {
            if (entry.ignored || entry.conflict != null) continue
            if (cached && entry.index == null) continue
            if (!cached && !entry.untracked && entry.index == null && entry.worktree == null) continue
            if (entry.untracked || unborn || !hasHunks(entry, cached)) {
                whole += WholeChange(entry.path, entry.originalPath, entry.untracked)
                continue
            }
            val paths = listOfNotNull(entry.path, entry.originalPath)
            val bytes =
                when (val r = runner.run(BlockDiffCommand(cached, paths, contextLines), repository)) {
                    is Result.Success -> r.data
                    is Result.Failure -> return r
                }
            // Without rename detection a renamed file is a deletion and an addition: the hunks are the new path's.
            val sections = RawPatchParser.parse(bytes)
            val hunks = sections.firstOrNull { it.hunks.isNotEmpty() }?.hunks.orEmpty()
            if (hunks.isEmpty()) {
                whole += WholeChange(entry.path, entry.originalPath, untracked = false)
            } else {
                files += FileBlocks(entry.path, entry.originalPath, HunkAnchors.of(hunks))
            }
        }
        return Result.Success(WorkingBlocks(files, whole))
    }

    /** Only a modified (or renamed) text file, with no submodule inside, can be split into hunks. */
    private fun hasHunks(entry: ChangeEntry, cached: Boolean): Boolean {
        if (entry.submodule != null) return false
        val sides = if (cached) listOfNotNull(entry.index) else listOfNotNull(entry.index, entry.worktree)
        return sides.isNotEmpty() && sides.all { it == ChangeStatus.MODIFIED || it == ChangeStatus.RENAMED }
    }
}
