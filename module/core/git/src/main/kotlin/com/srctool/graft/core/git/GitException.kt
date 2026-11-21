// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.graft.core.exception.CommandOutput
import com.srctool.graft.core.exception.GraftCodes
import com.srctool.graft.core.exception.Redaction
import com.srctool.sinew.models.ErrorMessage
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.SinewException

/**
 * A git process failed, or couldn't run. Every git failure is one of these subclasses, with a code like
 * `GIT-R-PUSH-REJ`: the git engine is a data source, so the layer is `R` (ADR-23).
 *
 * Git's own output is kept for "Show output": [argv] without the environment, [exitCode] (null when git couldn't be
 * started), and [stderr]. All three are redacted when the exception is created, so it can be logged or shown as is.
 */
public sealed class GitException(
    function: String,
    argv: List<String>,
    override val exitCode: Int?,
    stderr: String,
    cause: Throwable? = null,
) : SinewException("GIT", ExceptionLayer.Repository, GraftCodes.requireFunction(function), cause),
    CommandOutput {
    override val argv: List<String> = argv.map(Redaction::redact)
    override val stderr: String = Redaction.redact(stderr)

    /** Git's first `fatal:` or `error:` line, or an empty string. Technical detail for "Show output", not the message. */
    public val summary: String =
        this.stderr
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("fatal:") || it.startsWith("error:") }
            .orEmpty()

    protected fun local(key: String, fallback: String): ErrorMessage =
        ErrorMessage.Local(key = "git.error.$key", fallback = fallback)
}
