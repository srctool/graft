// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.repositories

import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.data.git.api.models.WorkingTree
import com.srctool.graft.data.git.api.repositories.WorkingTreeRepository
import com.srctool.graft.data.git.implementation.source.local.WorkingTreeCommand
import com.srctool.sinew.models.Result
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.nio.file.Path

internal class GitWorkingTreeRepository(private val runner: GitRunner) : WorkingTreeRepository {
    override suspend fun status(repository: Path, includeIgnored: Boolean): Result<WorkingTree> =
        runner.run(WorkingTreeCommand(includeIgnored), repository)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun changes(repository: Path, refreshes: Flow<Unit>, includeIgnored: Boolean): Flow<Result<WorkingTree>> =
        refreshes
            .onStart { emit(Unit) }
            // A refresh that arrives while a status runs waits, and the ones behind it fold into one.
            .conflate()
            .map { status(repository, includeIgnored) }
}
