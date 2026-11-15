// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.repositories

import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.data.git.api.models.CommitDetails
import com.srctool.graft.data.git.api.models.CommitLog
import com.srctool.graft.data.git.api.models.DiffTarget
import com.srctool.graft.data.git.api.models.LogQuery
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.repositories.CommitRepository
import com.srctool.graft.data.git.implementation.source.local.ChangedFilesCommand
import com.srctool.graft.data.git.implementation.source.local.DetailsCommand
import com.srctool.graft.data.git.implementation.source.local.LogCommand
import com.srctool.graft.data.git.implementation.source.local.logRevisions
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.map
import java.nio.file.Path

internal class GitCommitRepository(private val runner: GitRunner) : CommitRepository {
    override fun log(repository: Path): CommitLog {
        val command = LogCommand()
        return CommitLog(rows = runner.stream(command, repository), people = command.people)
    }

    override fun search(repository: Path, query: LogQuery): CommitLog {
        val command = LogCommand(query.logRevisions())
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
}
