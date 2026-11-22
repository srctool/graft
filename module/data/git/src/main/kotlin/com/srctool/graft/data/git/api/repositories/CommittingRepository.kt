// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.repositories

import com.srctool.graft.data.git.api.models.ChangeSelection
import com.srctool.graft.data.git.api.models.CoAuthor
import com.srctool.graft.data.git.api.models.CommitDraft
import com.srctool.graft.data.git.api.models.CommitMessageText
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.sinew.models.Result
import java.nio.file.Path

/**
 * Makes commits (Working Copy and Commit, "Commit"). Hooks run as git runs them, signing is whatever `commit.gpgsign`
 * and the rest of git's config say (Graft never handles keys), and the author and committer are git's.
 *
 * Every commit goes through the operation log as an operation Undo knows (`COMMIT` or `AMEND`). A commit that fails
 * (a hook that exits non-zero, signing with no key) writes nothing; git's stderr, which carries the hook's output, is
 * kept on the exception and in the log entry.
 */
public interface CommittingRepository {
    /**
     * Commits what is in the index, or, when [selection] is given, only the selected changes on top of `HEAD`: the
     * rest stays as it was in the working tree. A selection is committed through a scratch index, so the hooks see
     * exactly what is being committed. After it, the committed files' entries in the real index match the new
     * commit, and anything else staged is untouched, as `git commit --only` does.
     *
     * Refused before git runs (`GIT-R-CMIT-REFU`): a blank summary, a nothing-to-commit (unless
     * [CommitDraft.allowEmpty]), and a selection that selects no change. Returns the new commit.
     */
    public suspend fun commit(
        repository: Path,
        draft: CommitDraft,
        selection: ChangeSelection? = null,
        contextLines: Int = 3,
    ): Result<ObjectId>

    /**
     * Replaces the last commit with one made from [draft]'s message and, when [withStaged], what is staged now;
     * otherwise only the message changes and the index stays as it is (`--amend --only`). A first commit can't be
     * amended (`GIT-R-AMND-…`). Undo puts the branch back on the commit that was replaced.
     */
    public suspend fun amend(repository: Path, draft: CommitDraft, withStaged: Boolean = true): Result<ObjectId>

    /** The message of `HEAD`, offered when amending; null before the first commit (`GIT-R-PMSG-…`). */
    public suspend fun previousMessage(repository: Path): Result<CommitMessageText?>

    /**
     * The text of the repository's `commit.template`, for the commit box to start from; null when none is set. A
     * relative path is relative to the working tree's root, as git reads it (`GIT-R-TMPL-…`).
     */
    public suspend fun template(repository: Path): Result<String?>

    /** The most recent distinct authors in the repository, newest first, to suggest as co-authors (`GIT-R-AUTH-…`). */
    public suspend fun recentAuthors(repository: Path, limit: Int = 20): Result<List<CoAuthor>>
}
