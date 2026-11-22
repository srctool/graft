// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.graft.core.exception.GraftCodes.Types
import com.srctool.sinew.models.ErrorMessage

/** Why Graft didn't run `git commit` at all. */
public enum class CommitProblem {
    /** Nothing is staged (or selected) and the commit isn't an amend or an explicitly empty one. */
    NOTHING_TO_COMMIT,

    /** The summary is blank: git would abort on an empty message. */
    NO_SUMMARY,

    /** A co-author line that isn't `Name <email>`. [GitCommitRefusedException.detail] is the line. */
    BAD_CO_AUTHOR,
}

/**
 * A commit refused before git ran, so nothing was written and there is no git output. A commit git itself refused (a
 * failing hook, a signing failure) is a [GitExitException] with git's stderr instead.
 */
public class GitCommitRefusedException(
    function: String,
    public val problem: CommitProblem,
    public val detail: String = "",
) : GitException(function, listOf("commit"), exitCode = null, stderr = "") {
    override val typeCode: String = Types.REFUSED
    override val errorMessage: ErrorMessage =
        when (problem) {
            CommitProblem.NOTHING_TO_COMMIT ->
                local("commit.nothing", "There is nothing to commit. Stage some changes first.")

            CommitProblem.NO_SUMMARY -> local("commit.noSummary", "Write a summary for the commit.")

            CommitProblem.BAD_CO_AUTHOR ->
                ErrorMessage.Local(
                    key = "git.error.commit.badCoAuthor",
                    args = mapOf("line" to detail),
                    fallback = "\"{line}\" isn't a co-author. Write it as Name <email>.",
                )
        }
}
