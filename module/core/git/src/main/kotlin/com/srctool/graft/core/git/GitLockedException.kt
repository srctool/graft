// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.graft.core.exception.GraftCodes.Types
import com.srctool.sinew.models.ErrorMessage

/** Another git process holds a lock (`index.lock`, a ref lock). */
public class GitLockedException(function: String, argv: List<String>, exitCode: Int?, stderr: String) :
    GitException(function, argv, exitCode, stderr) {
    override val typeCode: String = Types.LOCKED
    override val retryable: Boolean get() = true
    override val errorMessage: ErrorMessage = local("locked", "Another Git process is running in this repository.")
}
