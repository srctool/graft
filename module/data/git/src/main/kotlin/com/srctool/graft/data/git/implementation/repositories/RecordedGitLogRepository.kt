// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.repositories

import com.srctool.graft.core.git.CommandLog
import com.srctool.graft.data.git.api.models.GitLogEntry
import com.srctool.graft.data.git.api.repositories.GitLogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.nio.file.Path

internal class RecordedGitLogRepository(private val log: CommandLog) : GitLogRepository {
    override fun entries(folder: Path): Flow<List<GitLogEntry>> = log.records
        .map { records ->
            records.filter { it.folder.startsWith(folder) }.map {
                GitLogEntry(it.startedAtMillis, it.folder, it.command, it.durationMillis, it.exitCode)
            }
        }.distinctUntilChanged()
}
