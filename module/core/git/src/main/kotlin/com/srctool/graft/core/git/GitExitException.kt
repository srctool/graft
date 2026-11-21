// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.graft.core.exception.GraftCodes.Types
import com.srctool.sinew.models.ErrorMessage

/** Git exited non-zero for a reason no rule recognized; [summary] and [stderr] say what git said. */
public class GitExitException(function: String, argv: List<String>, exitCode: Int?, stderr: String) :
    GitException(function, argv, exitCode, stderr) {
    override val typeCode: String = Types.EXIT
    override val errorMessage: ErrorMessage =
        ErrorMessage.Local(
            key = "git.error.exit",
            args = mapOf("exitCode" to (exitCode ?: -1)),
            fallback = "Git stopped with exit code {exitCode}.",
        )
}
