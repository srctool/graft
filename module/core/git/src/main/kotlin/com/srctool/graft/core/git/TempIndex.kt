// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.sinew.exception.ExceptionHandler
import com.srctool.sinew.exception.processCall
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * A scratch index for building trees without touching the user's: snapshots, shelves, and committing a changelist
 * all do `read-tree`, change the index, `write-tree` (Git Engine). Pass [environment] to each command that should use
 * it; the user's `.git/index` is never read or written through it.
 *
 * The file lives in `<git dir>/graft/tmp/`, on the repository's own filesystem so git's atomic renames work, and is
 * removed (with its `.lock`) when the scope ends, however it ends.
 */
public class TempIndex private constructor(public val file: Path) {
    /** `GIT_INDEX_FILE` for [GitInvocation.environment]. */
    public val environment: Map<String, String?> = mapOf("GIT_INDEX_FILE" to file.toString())

    public companion object {
        private const val FOLDER = "graft/tmp"

        /**
         * Runs [block] with a fresh temp index under [gitDir] (the worktree's own `$GIT_DIR`), and removes it after.
         * Failing to make the folder is a failure result; failures and exceptions from [block] pass through, and a
         * cancellation is rethrown after the cleanup.
         */
        public suspend fun <T> scope(gitDir: Path, block: suspend (TempIndex) -> Result<T>): Result<T> {
            val created =
                processCall("GIT", "TIDX", ExceptionLayer.Repository, handlers) {
                    withContext(Dispatchers.IO) {
                        val folder = Files.createDirectories(gitDir.resolve(FOLDER))
                        TempIndex(folder.resolve("index-${UUID.randomUUID()}"))
                    }
                }
            val index =
                when (created) {
                    is Result.Success -> created.data
                    is Result.Failure -> return created
                }
            return try {
                block(index)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { Files.deleteIfExists(index.file) }
                    runCatching { Files.deleteIfExists(Path.of("${index.file}.lock")) }
                }
            }
        }

        private val handlers =
            listOf(
                ExceptionHandler({ it is IOException }) {
                    GitExitException("TIDX", listOf("temporary index"), null, it.message.orEmpty())
                },
            )
    }
}
