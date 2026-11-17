// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.screens

import com.srctool.graft.data.app.api.models.AppSettings
import com.srctool.graft.data.app.api.models.Saved
import com.srctool.graft.data.app.api.models.SavedStatus
import com.srctool.graft.data.app.api.repositories.SettingsRepository
import com.srctool.graft.data.git.api.models.ChangedFile
import com.srctool.graft.data.git.api.models.DiffLine
import com.srctool.graft.data.git.api.models.DiffOptions
import com.srctool.graft.data.git.api.models.DiffTarget
import com.srctool.graft.data.git.api.models.FileContent
import com.srctool.graft.data.git.api.models.FileDiff
import com.srctool.graft.data.git.api.models.Hunk
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.repositories.DiffRepository
import com.srctool.graft.data.git.api.repositories.FileContents
import com.srctool.graft.feature.graph.presentation.graph.sampleFiles
import com.srctool.sinew.models.Result
import java.nio.file.Path

/** A commit's files ([sampleFiles]) with one hunk each; binaries have [image] bytes, sizes come from [sizes]. */
internal class FakeDiffs(
    var files: List<ChangedFile> = sampleFiles,
    var sizes: Map<ObjectId, Long> = emptyMap(),
    var image: ByteArray? = null,
) : DiffRepository {
    val optionsSeen = ArrayList<DiffOptions>()
    var closed = 0

    override suspend fun files(repository: Path, target: DiffTarget, options: DiffOptions): Result<List<ChangedFile>> =
        Result.Success(files)

    override suspend fun diff(
        repository: Path,
        target: DiffTarget,
        options: DiffOptions,
        paths: List<String>,
    ): Result<List<FileDiff>> {
        optionsSeen += options
        return Result.Success(
            files.filter { it.path in paths }.map { file ->
                val hunks =
                    if (file.status == com.srctool.graft.data.git.api.models.FileStatus.RENAMED &&
                        file.additions == 0
                    ) {
                        emptyList()
                    } else {
                        listOf(
                            Hunk(
                                10,
                                3,
                                10,
                                3,
                                "class Login",
                                listOf(
                                    DiffLine(DiffLine.Kind.CONTEXT, 10, 10, "fun login() {"),
                                    DiffLine(DiffLine.Kind.REMOVED, 11, null, "    val timeout = 30"),
                                    DiffLine(DiffLine.Kind.ADDED, null, 11, "    val timeout = 60"),
                                    DiffLine(DiffLine.Kind.CONTEXT, 12, 12, "}"),
                                ),
                            ),
                        )
                    }
                FileDiff(file, hunks)
            },
        )
    }

    override fun contents(repository: Path): FileContents = object : FileContents {
        override suspend fun read(revision: String, path: String, maxBytes: Long): Result<FileContent> =
            Result.Success(FileContent.Missing)

        override suspend fun read(id: ObjectId, maxBytes: Long): Result<FileContent> {
            val size = sizes[id] ?: 100
            return Result.Success(if (size > maxBytes) FileContent.TooLarge(size) else FileContent.Binary(size))
        }

        override suspend fun readBytes(id: ObjectId, maxBytes: Long): Result<ByteArray?> = Result.Success(image)

        override suspend fun message(id: ObjectId): Result<String> = Result.Success("Fix login timeout\n\nBody")

        override fun close() {
            closed++
        }
    }
}

internal class FakeSettings(var value: AppSettings = AppSettings()) : SettingsRepository {
    override suspend fun read(): Result<Saved<AppSettings>> = Result.Success(Saved(value, SavedStatus.Current))

    override suspend fun update(change: (AppSettings) -> AppSettings): Result<AppSettings> {
        value = change(value)
        return Result.Success(value)
    }
}
