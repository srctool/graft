// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.sinew.models.Result
import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

/**
 * The only way Graft runs git (ADR-2): the `git` executable with an argv list, never a shell, machine-readable
 * output only.
 *
 * Results are Sinew's [Result] (ADR-23). A failure is a [GitException] subclass
 * with git's argv, exit code and stderr, or Sinew's `ParseException` when the output can't be read. Cancelling a
 * call kills the whole process tree, and the cancellation propagates: it's never a failure.
 */
public interface GitRunner {
    /** Runs [cmd] in [cwd], writing [input] to its stdin (stdin is closed right away when there's none). */
    public suspend fun <T> run(cmd: GitCommand<T>, cwd: Path, input: ByteArray? = null): Result<T>

    /**
     * Streams [cmd]'s parsed output. The process starts when the flow is collected and is killed when collection
     * stops early. A failure is thrown as the same exceptions [run] returns.
     */
    public fun <T> stream(cmd: StreamingGitCommand<T>, cwd: Path): Flow<T>
}
