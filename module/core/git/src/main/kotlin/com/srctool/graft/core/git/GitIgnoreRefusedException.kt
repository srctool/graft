// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.graft.core.exception.GraftCodes.Types
import com.srctool.sinew.models.ErrorMessage

/** Why Ignore or Clean did nothing. */
public enum class IgnoreProblem {
    /** A pattern or a folder that can't be written safely: a line break in a pattern, a folder outside the working tree. */
    UNSAFE_INPUT,

    /** A path asked to be cleaned that the preview doesn't list now: the working tree changed since it was shown. */
    NOT_IN_PREVIEW,

    /** A nested repository named without the user's explicit yes for it. */
    NESTED_REPOSITORY,
}

/** Ignore or Clean refused before changing anything. [detail] is the path or pattern concerned. */
public class GitIgnoreRefusedException(function: String, public val problem: IgnoreProblem, public val detail: String) :
    GitException(function, listOf("ignore"), exitCode = null, stderr = "") {
    override val typeCode: String = Types.REFUSED
    override val errorMessage: ErrorMessage =
        when (problem) {
            IgnoreProblem.UNSAFE_INPUT ->
                ErrorMessage.Local(
                    key = "git.error.ignore.unsafe",
                    args = mapOf("what" to detail),
                    fallback = "{what} can't be written to an ignore file.",
                )

            IgnoreProblem.NOT_IN_PREVIEW ->
                ErrorMessage.Local(
                    key = "git.error.clean.notInPreview",
                    args = mapOf("path" to detail),
                    fallback = "{path} is no longer something clean would remove. Preview again.",
                )

            IgnoreProblem.NESTED_REPOSITORY ->
                ErrorMessage.Local(
                    key = "git.error.clean.nested",
                    args = mapOf("path" to detail),
                    fallback = "{path} is a repository of its own. Confirm it by name to remove it.",
                )
        }
}
