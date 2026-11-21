// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.graft.core.exception.GraftCodes.Types
import com.srctool.sinew.models.ErrorMessage

/** The operation took longer than allowed. */
public class GitTimeoutException(function: String, argv: List<String>, exitCode: Int?, stderr: String) :
    GitException(function, argv, exitCode, stderr) {
    override val typeCode: String = Types.TIMEOUT
    override val retryable: Boolean get() = true
    override val errorMessage: ErrorMessage = local("timeout", "Git took too long to answer.")
}
