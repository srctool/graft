// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.source.local

import com.srctool.graft.core.git.GitCommand
import com.srctool.graft.data.git.api.models.CoAuthor
import com.srctool.graft.data.git.api.models.CommitDraft

/**
 * `git commit` with the message on stdin (`-F -`), so it is never an argument or a shell string. The cleanup keeps the
 * message as written apart from stray blank lines: no `#` lines are dropped. Co-authors go in as `--trailer`, so git
 * places them with any sign-off. [index] names a scratch index to commit instead of the repository's.
 *
 * Hooks run as git runs them: their output arrives on stderr, which the runner keeps on a failure.
 */
internal class CommitCommand(
    draft: CommitDraft,
    amend: Boolean,
    withStaged: Boolean = true,
    index: Map<String, String?> = emptyMap(),
) : GitCommand<Unit>() {
    override val function = if (amend) "AMND" else "CMIT"
    override val mutates = true
    override val environment = index
    override val args =
        buildList {
            addAll(listOf("commit", "--quiet", "--cleanup=whitespace"))
            if (amend) {
                add("--amend")
                // Without paths, `--amend --only` changes the message and leaves the index as it is.
                if (!withStaged) add("--only")
            }
            if (draft.signOff) add("--signoff")
            if (draft.noVerify) add("--no-verify")
            // Amending can't make a commit emptier than it was, and git refuses to amend an empty one without this.
            if (draft.allowEmpty || amend) add("--allow-empty")
            draft.coAuthors.forEach { add("--trailer=Co-authored-by: $it") }
            addAll(listOf("-F", "-"))
        }

    override fun parse(stdout: ByteArray) = Unit
}

/** Whether the index [index] (the repository's when empty) differs from `HEAD`: its changed paths, NUL-separated. */
internal class StagedPathsCommand(index: Map<String, String?> = emptyMap()) : GitCommand<Boolean>() {
    override val function = "CMIT"
    override val args = listOf("diff", "--cached", "--name-only", "-z", "--no-renames")
    override val environment = index

    override fun parse(stdout: ByteArray): Boolean = stdout.isNotEmpty()
}

/** Fills a scratch index with `HEAD`'s tree, or leaves it empty before the first commit. */
internal class ReadHeadTreeCommand(unborn: Boolean, index: Map<String, String?>) : GitCommand<Unit>() {
    override val function = "RTRE"
    override val args = if (unborn) listOf("read-tree", "--empty") else listOf("read-tree", "HEAD")
    override val environment = index

    override fun parse(stdout: ByteArray) = Unit
}

/** The message of `HEAD`, as stored. */
internal class HeadMessageCommand : GitCommand<String>() {
    override val function = "PMSG"
    override val args = listOf("log", "-1", "--format=%B")

    override fun parse(stdout: ByteArray): String = GitText.decode(stdout)
}

/** `commit.template` as a path with `~` expanded, or null when it isn't set (`config --get` exits 1). */
internal class TemplatePathCommand : GitCommand<String?>() {
    override val function = "TMPL"
    override val args = listOf("config", "--get", "--type=path", "commit.template")
    override val successExitCodes = setOf(0, 1)

    override fun parse(stdout: ByteArray): String? = GitText.decode(stdout).trim('\n', '\r').takeIf { it.isNotEmpty() }
}

/** Name and email of the last [scan] commits of `HEAD`, as NUL-separated pairs (newest first). */
internal class RecentAuthorsCommand(scan: Int) : GitCommand<List<CoAuthor>>() {
    override val function = "AUTH"
    override val args = listOf("log", "-z", "-n", scan.toString(), "--format=%an%x00%ae")

    override fun parse(stdout: ByteArray): List<CoAuthor> =
        GitText.decode(stdout).split('\u0000').filter { it.isNotEmpty() }.chunked(2).mapNotNull {
            if (it.size == 2 && it[0].isNotBlank() && it[1].isNotBlank()) CoAuthor(it[0], it[1]) else null
        }
}
