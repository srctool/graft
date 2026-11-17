// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.repositories

import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.data.git.api.models.CommitDetails
import com.srctool.graft.data.git.api.models.CommitLog
import com.srctool.graft.data.git.api.models.DiffTarget
import com.srctool.graft.data.git.api.models.LogQuery
import com.srctool.graft.data.git.api.models.LogScope
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.repositories.CommitRepository
import com.srctool.graft.data.git.implementation.source.local.ChangedFilesCommand
import com.srctool.graft.data.git.implementation.source.local.CommitGraphPathsCommand
import com.srctool.graft.data.git.implementation.source.local.CommitGraphWriteCommand
import com.srctool.graft.data.git.implementation.source.local.DetailsCommand
import com.srctool.graft.data.git.implementation.source.local.LogCommand
import com.srctool.graft.data.git.implementation.source.local.logRevisions
import com.srctool.graft.data.git.implementation.source.local.revisions
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.map
import java.nio.file.Files
import java.nio.file.Path

internal class GitCommitRepository(private val runner: GitRunner) : CommitRepository {
    override fun log(repository: Path, scope: LogScope, firstParent: Boolean): CommitLog {
        val command = LogCommand(if (firstParent) listOf("--first-parent") + scope.revisions() else scope.revisions())
        return CommitLog(rows = runner.stream(command, repository), people = command.people)
    }

    override fun search(repository: Path, query: LogQuery, scope: LogScope): CommitLog {
        val command = LogCommand(query.logRevisions(scope))
        return CommitLog(rows = runner.stream(command, repository), people = command.people)
    }

    override suspend fun details(repository: Path, id: ObjectId): Result<CommitDetails> {
        val metadata =
            when (val r = runner.run(DetailsCommand(id), repository)) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        return runner.run(ChangedFilesCommand(DiffTarget.Commit(id)), repository).map(metadata::withFiles)
    }

    override suspend fun hasCommitGraph(repository: Path): Result<Boolean> =
        runner.run(CommitGraphPathsCommand(), repository).map { paths ->
            paths.any { Files.exists(repository.resolve(it)) }
        }

    override suspend fun writeCommitGraph(repository: Path): Result<Unit> =
        runner.run(CommitGraphWriteCommand(), repository)
}
