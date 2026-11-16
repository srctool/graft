// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.exception

/**
 * What a failed command printed, for an error's **Show output** (UX Writing, "Errors"): screens read it from any error
 * that carries it without depending on the module that ran the command. Already redacted (no tokens, no credential
 * helper input).
 */
public interface CommandOutput {
    /** The command line as it ran, redacted. */
    public val argv: List<String>

    /** The exit code, or null when the command didn't start or was stopped. */
    public val exitCode: Int?

    /** Standard error, redacted. */
    public val stderr: String
}
