// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.models

import java.nio.file.Path

/** One git command Graft ran, as the Git log shows it: [command] is redacted, [exitCode] null if git never ran. */
public data class GitLogEntry(
    val startedAtMillis: Long,
    val folder: Path,
    val command: String,
    val durationMillis: Long,
    val exitCode: Int?,
) {
    val failed: Boolean get() = exitCode != 0
}
