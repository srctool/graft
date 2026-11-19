// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.repositories

import com.srctool.graft.core.git.GitException
import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.data.git.api.models.FileList
import com.srctool.graft.data.git.api.repositories.TreeRepository
import com.srctool.graft.data.git.implementation.source.local.FilesInHeadCommand
import com.srctool.sinew.models.Result
import java.nio.file.Path

internal class GitTreeRepository(private val runner: GitRunner) : TreeRepository {
    override suspend fun files(repository: Path, limit: Int): Result<FileList> =
        when (val r = runner.run(FilesInHeadCommand(limit), repository)) {
            is Result.Success -> r

            // No commits yet: HEAD names nothing, and an empty tree is the honest answer.
            is Result.Failure ->
                if ((r.error as? GitException)?.stderr?.contains("Not a valid object name") == true) {
                    Result.Success(FileList(emptyList(), truncated = false))
                } else {
                    r
                }
        }
}
