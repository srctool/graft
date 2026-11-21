// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.graft.core.exception.GraftCodes.Types
import com.srctool.sinew.models.ErrorMessage

/** The folder isn't a repository, or the `git` executable isn't there ([exitCode] is null then). */
public class GitNotFoundException(
    function: String,
    argv: List<String>,
    exitCode: Int?,
    stderr: String,
    cause: Throwable? = null,
) : GitException(function, argv, exitCode, stderr, cause) {
    override val typeCode: String = Types.NOT_FOUND
    override val errorMessage: ErrorMessage =
        if (exitCode ==
            null
        ) {
            local("missing", "Git isn't installed, or Graft can't start it.")
        } else {
            local("notRepository", "This folder isn't a Git repository.")
        }
}
