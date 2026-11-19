// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.graft.core.exception.Redaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.file.Path

/**
 * One git command the runner ran, for the Git log tool window ("show the command", UI Design). [command] is the
 * argument list after the executable and the runner's fixed options, already redacted; stdout is never kept.
 */
public data class CommandRecord(
    val startedAtMillis: Long,
    val folder: Path,
    val command: String,
    val durationMillis: Long,
    /** Git's exit code; null when git couldn't start or the call was cancelled. */
    val exitCode: Int?,
)

/**
 * The last [capacity] commands, newest last, kept in memory only. A runner given one records into it; the app shows it.
 */
public class CommandLog(private val capacity: Int = DEFAULT_CAPACITY) {
    private val state = MutableStateFlow<List<CommandRecord>>(emptyList())

    public val records: StateFlow<List<CommandRecord>> = state.asStateFlow()

    /** Adds one run; [args] is redacted here, so no caller has to remember to. */
    public fun record(startedAtMillis: Long, folder: Path, args: List<String>, durationMillis: Long, exitCode: Int?) {
        val entry =
            CommandRecord(startedAtMillis, folder, Redaction.redact(args.joinToString(" ")), durationMillis, exitCode)
        state.update { (it + entry).takeLast(capacity) }
    }

    public companion object {
        public const val DEFAULT_CAPACITY: Int = 500
    }
}
