// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.repositories

import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.data.git.api.models.Ref
import com.srctool.graft.data.git.api.repositories.RefRepository
import com.srctool.graft.data.git.implementation.source.local.RefsCommand
import com.srctool.sinew.models.Result
import java.nio.file.Path

internal class GitRefRepository(private val runner: GitRunner) : RefRepository {
    override suspend fun refs(repository: Path): Result<List<Ref>> = runner.run(RefsCommand(), repository)
}
