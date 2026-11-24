// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.graft.core.exception.GraftCodes.Types
import com.srctool.sinew.models.ErrorMessage

/** Why Commit Guards didn't write an allow-list. */
public enum class GuardProblem {
    /** The file was saved by a newer Graft: it is read-only here, never rewritten (Storage Formats, rule 3). */
    NEWER_FORMAT,

    /** `.graft/guard.toml` can't be read, so adding to it would lose what is there. */
    UNREADABLE_FILE,
}

/** An allow-list left alone because writing it would lose data. [detail] is the file's path. */
public class GitGuardRefusedException(function: String, public val problem: GuardProblem, public val detail: String) :
    GitException(function, listOf("guard"), exitCode = null, stderr = "") {
    override val typeCode: String = Types.REFUSED
    override val errorMessage: ErrorMessage =
        when (problem) {
            GuardProblem.NEWER_FORMAT ->
                ErrorMessage.Local(
                    key = "git.error.guard.newerFormat",
                    args = mapOf("file" to detail),
                    fallback = "{file} was saved by a newer Graft. This version reads it but doesn't change it.",
                )

            GuardProblem.UNREADABLE_FILE ->
                ErrorMessage.Local(
                    key = "git.error.guard.unreadable",
                    args = mapOf("file" to detail),
                    fallback = "{file} can't be read, so Graft won't change it. Fix or remove the file first.",
                )
        }
}
