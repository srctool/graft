// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.graft.core.exception.GraftCodes.Types
import com.srctool.sinew.models.ErrorMessage

/** The remote refused the update (non-fast-forward, protected branch, hook). */
public class GitRejectedException(function: String, argv: List<String>, exitCode: Int?, stderr: String) :
    GitException(function, argv, exitCode, stderr) {
    override val typeCode: String = Types.REJECTED
    override val errorMessage: ErrorMessage = local("rejected", "The remote rejected the change.")
}
