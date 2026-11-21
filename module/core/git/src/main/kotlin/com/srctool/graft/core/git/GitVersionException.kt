// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.graft.core.exception.GraftCodes.Types
import com.srctool.sinew.models.ErrorMessage

/** The installed Git is older than Graft supports. */
public class GitVersionException(
    function: String,
    argv: List<String>,
    public val found: String,
    public val required: String,
) : GitException(function, argv, exitCode = 0, stderr = "") {
    override val typeCode: String = Types.VERSION
    override val errorMessage: ErrorMessage =
        ErrorMessage.Local(
            key = "git.error.version",
            args = mapOf("found" to found, "required" to required),
            fallback = "Git {found} is too old; Graft needs Git {required} or newer.",
        )
}
