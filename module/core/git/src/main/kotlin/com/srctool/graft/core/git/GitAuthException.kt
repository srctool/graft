// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.graft.core.exception.GraftCodes.Types
import com.srctool.sinew.models.ErrorMessage

/** Authentication failed or needs a prompt git isn't allowed to show. */
public class GitAuthException(function: String, argv: List<String>, exitCode: Int?, stderr: String) :
    GitException(function, argv, exitCode, stderr) {
    override val typeCode: String = Types.AUTH
    override val errorMessage: ErrorMessage = local("auth", "Git couldn't sign in to the remote.")
}
