// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.repositories

import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.data.git.api.models.AheadBehind
import com.srctool.graft.data.git.api.models.CommitLog
import com.srctool.graft.data.git.api.models.MergePreview
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.repositories.CompareRepository
import com.srctool.graft.data.git.implementation.source.local.AheadBehindCommand
import com.srctool.graft.data.git.implementation.source.local.LogCommand
import com.srctool.graft.data.git.implementation.source.local.MergeTreeCommand
import com.srctool.graft.data.git.implementation.source.local.OptionalMergeBaseCommand
import com.srctool.sinew.models.Result
import java.nio.file.Path

internal class GitCompareRepository(private val runner: GitRunner) : CompareRepository {
    override fun commits(repository: Path, from: String, to: String): CommitLog {
        val command = LogCommand(listOf("$from..$to", "--"))
        return CommitLog(rows = runner.stream(command, repository), people = command.people)
    }

    override suspend fun aheadBehind(repository: Path, base: String, compare: String): Result<AheadBehind> =
        runner.run(AheadBehindCommand(base, compare), repository)

    override suspend fun mergeBase(repository: Path, a: String, b: String): Result<ObjectId?> =
        runner.run(OptionalMergeBaseCommand(a, b), repository)

    override suspend fun mergePreview(repository: Path, into: String, from: String): Result<MergePreview> =
        runner.run(MergeTreeCommand(into, from), repository)
}
