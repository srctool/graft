// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.repositories

import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.data.git.api.models.ReflogEntry
import com.srctool.graft.data.git.api.models.ReflogPage
import com.srctool.graft.data.git.api.models.Remote
import com.srctool.graft.data.git.api.models.StashEntry
import com.srctool.graft.data.git.api.repositories.ReflogRepository
import com.srctool.graft.data.git.api.repositories.RemoteRepository
import com.srctool.graft.data.git.api.repositories.StashRepository
import com.srctool.graft.data.git.implementation.source.local.LocateCommand
import com.srctool.graft.data.git.implementation.source.local.LogAllRefUpdatesCommand
import com.srctool.graft.data.git.implementation.source.local.ReflogCommand
import com.srctool.graft.data.git.implementation.source.local.RemotesCommand
import com.srctool.graft.data.git.implementation.source.local.StashListCommand
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.map
import java.nio.file.Path

internal class GitReflogRepository(private val runner: GitRunner) : ReflogRepository {
    override suspend fun entries(repository: Path, ref: String, skip: Int, limit: Int): Result<ReflogPage> {
        require(skip >= 0 && limit > 0) { "skip must be ≥ 0 and limit > 0" }
        // One entry more than asked: its new id is the last entry's old id, and it says whether more remain.
        return runner.run(ReflogCommand(ref, skip, limit + 1), repository).map { raw ->
            val shown = raw.take(limit)
            ReflogPage(
                entries =
                shown.mapIndexed { i, e ->
                    ReflogEntry(
                        ref = ref,
                        index = skip + i,
                        oldId = raw.getOrNull(i + 1)?.newId,
                        newId = e.newId,
                        time = e.time,
                        offsetMinutes = e.offsetMinutes,
                        actor = e.actor,
                        message = e.message,
                        subject = e.subject,
                    )
                },
                hasMore = raw.size > limit,
            )
        }
    }

    override suspend fun enabled(repository: Path): Result<Boolean> {
        val setting =
            when (val r = runner.run(LogAllRefUpdatesCommand(), repository)) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        return when (setting?.lowercase()) {
            "true", "always", "yes", "on", "1" -> Result.Success(true)

            "false", "no", "off", "0" -> Result.Success(false)

            // Unset: git logs in a repository with a working tree, not in a bare one.
            else -> runner.run(LocateCommand(), repository).map { !it.bare }
        }
    }
}

internal class GitStashRepository(private val runner: GitRunner) : StashRepository {
    override suspend fun list(repository: Path): Result<List<StashEntry>> = runner.run(StashListCommand(), repository)
}

internal class GitRemoteRepository(private val runner: GitRunner) : RemoteRepository {
    override suspend fun list(repository: Path): Result<List<Remote>> = runner.run(RemotesCommand(), repository)
}
