// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.graft.core.exception.GraftCodes.Types
import com.srctool.sinew.models.ErrorMessage

/** Why `git apply` refused a patch. */
public enum class PatchProblem {
    /** The file (or the index) no longer matches the patch's context. */
    DOES_NOT_APPLY,

    /** The patch creates a file that is already there. */
    ALREADY_EXISTS,

    /** The patch itself is damaged. */
    CORRUPT,
}

/** `git apply` refused a patch ([problem] says how); git's own words are in [stderr]. */
public class GitPatchException(
    function: String,
    argv: List<String>,
    exitCode: Int?,
    stderr: String,
    public val problem: PatchProblem,
) : GitException(function, argv, exitCode, stderr) {
    override val typeCode: String = Types.PATCH
    override val errorMessage: ErrorMessage =
        when (problem) {
            PatchProblem.DOES_NOT_APPLY ->
                local("patch.noFit", "The change doesn't fit the file as it is now.")

            PatchProblem.ALREADY_EXISTS ->
                local("patch.exists", "The change adds a file that already exists.")

            PatchProblem.CORRUPT -> local("patch.corrupt", "The change is damaged and can't be applied.")
        }
}

/**
 * The failure for a `git apply` that refused its patch, or null when [stderr] says something else. A command that
 * applies patches returns this from its `failure` rule.
 */
public fun patchFailure(function: String, argv: List<String>, exitCode: Int, stderr: String): GitPatchException? {
    val problem =
        when {
            "corrupt patch" in stderr || "patch with only garbage" in stderr ||
                "patch fragment without header" in stderr ->
                PatchProblem.CORRUPT

            "already exists in working directory" in stderr || "already exists in index" in stderr ->
                PatchProblem.ALREADY_EXISTS

            "patch does not apply" in stderr || "patch failed:" in stderr || "does not match index" in stderr ->
                PatchProblem.DOES_NOT_APPLY

            else -> return null
        }
    return GitPatchException(function, argv, exitCode, stderr, problem)
}
