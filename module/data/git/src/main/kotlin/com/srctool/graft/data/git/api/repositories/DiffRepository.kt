// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.repositories

import com.srctool.graft.data.git.api.models.ChangedFile
import com.srctool.graft.data.git.api.models.DiffOptions
import com.srctool.graft.data.git.api.models.DiffTarget
import com.srctool.graft.data.git.api.models.FileContent
import com.srctool.graft.data.git.api.models.FileDiff
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.sinew.models.Result
import java.nio.file.Path

/** Diffs and file contents for the diff viewer (Screen Inventory S6) and Compare (S7). */
public interface DiffRepository {
    /** The files [target] changes, with line counts (`GIT-R-FILS-…`). */
    public suspend fun files(
        repository: Path,
        target: DiffTarget,
        options: DiffOptions = DiffOptions(),
    ): Result<List<ChangedFile>>

    /**
     * The changes of [target] file by file, hunks and numbered lines; only [paths] when given (the viewer asks for
     * the file the user opened) (`GIT-R-DIFF-…`).
     */
    public suspend fun diff(
        repository: Path,
        target: DiffTarget,
        options: DiffOptions = DiffOptions(),
        paths: List<String> = emptyList(),
    ): Result<List<FileDiff>>

    /** File contents for [repository], read through one long-lived git process. The caller closes it. */
    public fun contents(repository: Path): FileContents
}

/** Reads files and messages at any revision without a process per read. One per repository session. */
public interface FileContents : AutoCloseable {
    /** [path] at [revision]; files over [maxBytes] (the inline diff size limit) aren't loaded (`GIT-R-CATF-…`). */
    public suspend fun read(revision: String, path: String, maxBytes: Long = DEFAULT_MAX_BYTES): Result<FileContent>

    /** The file stored as object [id] (a [com.srctool.graft.data.git.api.models.ChangedFile]'s old or new object). */
    public suspend fun read(id: ObjectId, maxBytes: Long = DEFAULT_MAX_BYTES): Result<FileContent>

    /**
     * The raw bytes of blob [id], for the image diff: null when it's over [maxBytes] or isn't a blob (`GIT-R-CATF-…`).
     */
    public suspend fun readBytes(id: ObjectId, maxBytes: Long = DEFAULT_MAX_BYTES): Result<ByteArray?>

    /** Commit [id]'s full message, for rows the graph loaded with their subject only. */
    public suspend fun message(id: ObjectId): Result<String>

    public companion object {
        /** The inline diff size limit's default (Settings Reference). */
        public const val DEFAULT_MAX_BYTES: Long = 2L * 1024 * 1024
    }
}
