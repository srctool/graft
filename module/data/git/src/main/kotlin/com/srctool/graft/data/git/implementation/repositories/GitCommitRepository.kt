// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.repositories

import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.data.git.api.models.CommitLog
import com.srctool.graft.data.git.api.repositories.CommitRepository
import com.srctool.graft.data.git.implementation.source.local.LogCommand
import java.nio.file.Path

internal class GitCommitRepository(private val runner: GitRunner) : CommitRepository {
    override fun log(repository: Path): CommitLog {
        val command = LogCommand()
        return CommitLog(rows = runner.stream(command, repository), people = command.people)
    }
}
