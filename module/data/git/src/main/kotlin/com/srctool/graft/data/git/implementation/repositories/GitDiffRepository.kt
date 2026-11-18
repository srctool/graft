// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.repositories

import com.srctool.graft.core.git.GitObject
import com.srctool.graft.core.git.GitObjectReader
import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.data.git.api.models.ChangedFile
import com.srctool.graft.data.git.api.models.DiffOptions
import com.srctool.graft.data.git.api.models.DiffTarget
import com.srctool.graft.data.git.api.models.FileContent
import com.srctool.graft.data.git.api.models.FileDiff
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.repositories.DiffRepository
import com.srctool.graft.data.git.api.repositories.FileContents
import com.srctool.graft.data.git.implementation.source.local.ChangedFilesCommand
import com.srctool.graft.data.git.implementation.source.local.GitText
import com.srctool.graft.data.git.implementation.source.local.MergeBaseCommand
import com.srctool.graft.data.git.implementation.source.local.PatchCommand
import com.srctool.graft.data.git.implementation.source.local.PatchParser
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.map
import java.nio.file.Path

internal class GitDiffRepository(private val runner: GitRunner) : DiffRepository {
    override suspend fun files(repository: Path, target: DiffTarget, options: DiffOptions): Result<List<ChangedFile>> {
        val resolved =
            when (val r = resolve(repository, target)) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        return runner.run(ChangedFilesCommand(resolved, options), repository)
    }

    override suspend fun diff(
        repository: Path,
        target: DiffTarget,
        options: DiffOptions,
        paths: List<String>,
    ): Result<List<FileDiff>> {
        val resolved =
            when (val r = resolve(repository, target)) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        // Two commands: the file list for exact paths and line counts, the patch for the lines. Same order in both.
        val files =
            when (val r = runner.run(ChangedFilesCommand(resolved, options, paths), repository)) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        val patch = PatchCommand(resolved, options, paths)
        return runner.run(patch, repository).map { bytes -> PatchParser.parse(bytes, files) }
    }

    override fun contents(repository: Path): FileContents = GitFileContents(runner.objectReader(repository))

    /** `A...B` becomes "from the merge base to B"; the other targets pass through. */
    private suspend fun resolve(repository: Path, target: DiffTarget): Result<DiffTarget> = when (target) {
        is DiffTarget.Since ->
            runner.run(MergeBaseCommand(target.base, target.tip), repository).map { base ->
                DiffTarget.Between(base.hex, target.tip)
            }

        else -> Result.Success(target)
    }
}

private class GitFileContents(private val reader: GitObjectReader) : FileContents {
    override suspend fun read(revision: String, path: String, maxBytes: Long): Result<FileContent> =
        load("$revision:$path", maxBytes)

    override suspend fun read(id: ObjectId, maxBytes: Long): Result<FileContent> = load(id.hex, maxBytes)

    private suspend fun load(spec: String, maxBytes: Long): Result<FileContent> =
        reader.read(spec, maxBytes).map { obj ->
            when (obj) {
                is GitObject.Found ->
                    when {
                        obj.type != "blob" -> FileContent.Missing
                        obj.bytes.looksBinary() -> FileContent.Binary(obj.bytes.size.toLong())
                        else -> FileContent.Text(GitText.decode(obj.bytes))
                    }

                is GitObject.TooLarge -> FileContent.TooLarge(obj.size)

                GitObject.Missing -> FileContent.Missing
            }
        }

    override suspend fun readBytes(id: ObjectId, maxBytes: Long): Result<ByteArray?> =
        reader.read(id.hex, maxBytes).map { obj -> (obj as? GitObject.Found)?.takeIf { it.type == "blob" }?.bytes }

    override suspend fun message(id: ObjectId): Result<String> = reader.read(id.hex, Long.MAX_VALUE).map { obj ->
        val raw = (obj as? GitObject.Found)?.takeIf { it.type == "commit" }?.bytes ?: return@map ""
        // A commit object is headers, a blank line, then the message.
        val start = (0 until raw.size - 1).firstOrNull { raw[it] == NL && raw[it + 1] == NL }?.plus(2) ?: raw.size
        GitText.decode(raw, start, raw.size - start).trimEnd('\n')
    }

    override fun close() = reader.close()

    /** Git's own test: a NUL in the first 8,000 bytes. */
    private fun ByteArray.looksBinary(): Boolean = (0 until minOf(size, 8_000)).any { this[it] == 0.toByte() }

    private companion object {
        const val NL: Byte = '\n'.code.toByte()
    }
}
