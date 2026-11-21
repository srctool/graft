// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.graft.core.exception.GraftCodes.Types
import com.srctool.sinew.models.ErrorMessage

/**
 * Undo or redo refused, with [reason] in words: the branch moved since the operation, or HEAD is somewhere else now.
 * Nothing was changed. Code `GIT-R-UNDO-MOVD`.
 */
public class GitUndoRefusedException(public val reason: String) :
    GitException("UNDO", listOf("update-ref"), exitCode = null, stderr = "") {
    override val typeCode: String = Types.MOVED
    override val errorMessage: ErrorMessage =
        ErrorMessage.Local(
            key = "git.error.undoRefused",
            args = mapOf("reason" to reason),
            fallback = "Can't undo this: {reason}",
        )
}
