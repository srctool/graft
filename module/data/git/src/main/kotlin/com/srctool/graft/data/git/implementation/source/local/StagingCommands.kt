// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.source.local

import com.srctool.graft.core.git.GitCommand
import com.srctool.graft.core.git.GitException
import com.srctool.graft.core.git.patchFailure

/** Paths are literal, never patterns: a file called `*.txt` is one file. */
private val LITERAL = mapOf("GIT_LITERAL_PATHSPECS" to "1")

/** NUL-separated paths for `--pathspec-from-file=- --pathspec-file-nul`, which has no limit and no quoting rules. */
internal fun pathspecInput(paths: List<String>): ByteArray =
    paths.joinToString(separator = "") { "$it\u0000" }.toByteArray(Charsets.UTF_8)

private val PATHSPEC_FROM_STDIN = listOf("--pathspec-from-file=-", "--pathspec-file-nul")

/** Whether the folder is a repository at all: `git diff` outside one compares two files on disk instead. */
internal class InsideRepositoryCommand : GitCommand<Unit>() {
    override val function = "RPRS"
    override val args = listOf("rev-parse", "--git-dir")

    override fun parse(stdout: ByteArray) = Unit
}

/** The paths git has an entry for (tracked or intent-to-add); anything else is untracked. */
internal class TrackedPathsCommand(paths: List<String>) : GitCommand<Set<String>>() {
    override val function = "LSF"
    override val args = listOf("ls-files", "-z", "--") + paths
    override val environment = LITERAL

    override fun parse(stdout: ByteArray): Set<String> =
        stdout.toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }.toSet()
}

/** A diff as bytes, fixed so user config can't change it: [cached] is HEAD against the index, else index against files. */
internal class StagingDiffCommand(cached: Boolean, paths: List<String>, contextLines: Int) : GitCommand<ByteArray>() {
    override val function = "DIFF"
    override val args =
        buildList {
            add("diff")
            if (cached) add("--cached")
            addAll(listOf("-p", "--no-color", "--no-ext-diff", "--no-textconv", "--src-prefix=a/", "--dst-prefix=b/"))
            addAll(listOf("--submodule=short", "--unified=$contextLines", "-M", "--"))
            addAll(paths)
        }
    override val environment = LITERAL

    override fun parse(stdout: ByteArray): ByteArray = stdout
}

/** A new file against nothing, for lines of a file git has no entry for yet; `--no-index` exits 1 when files differ. */
internal class NewFileDiffCommand(path: String, contextLines: Int) : GitCommand<ByteArray>() {
    override val function = "DIFF"
    override val args =
        listOf(
            "diff", "--no-index", "-p", "--no-color", "--no-ext-diff", "--no-textconv", "--src-prefix=a/",
            "--dst-prefix=b/", "--unified=$contextLines", "--", "/dev/null", path,
        )
    override val successExitCodes = setOf(0, 1)

    override fun parse(stdout: ByteArray): ByteArray = stdout
}

/** Applies a patch from stdin to the index ([cached]) or the files; [reverse] undoes it instead (`-R`). */
internal class ApplyPatchCommand(cached: Boolean, reverse: Boolean) : GitCommand<Unit>() {
    override val function = "APLY"
    override val mutates = true
    override val args =
        buildList {
            add("apply")
            if (cached) add("--cached")
            if (reverse) add("-R")
            addAll(listOf("--whitespace=nowarn", "-"))
        }

    override fun parse(stdout: ByteArray) = Unit

    override fun failure(argv: List<String>, exitCode: Int, stderr: String): GitException? =
        patchFailure(function, argv, exitCode, stderr)
}

/** `git add` for whole files: new, changed and deleted alike. Paths come on stdin. */
internal class AddCommand : GitCommand<Unit>() {
    override val function = "ADD"
    override val mutates = true
    override val args = listOf("add", "-A") + PATHSPEC_FROM_STDIN
    override val environment = LITERAL

    override fun parse(stdout: ByteArray) = Unit
}

/** Takes whole files out of the index (`git reset -- paths`), which also works before the first commit. */
internal class ResetPathsCommand : GitCommand<Unit>() {
    override val function = "RSET"
    override val mutates = true
    override val args = listOf("reset", "-q") + PATHSPEC_FROM_STDIN
    override val environment = LITERAL

    override fun parse(stdout: ByteArray) = Unit
}

/** Whole tracked files in the working tree back to the index. Paths come on stdin. */
internal class RestoreWorktreeCommand : GitCommand<Unit>() {
    override val function = "REST"
    override val mutates = true
    override val args = listOf("restore", "--worktree") + PATHSPEC_FROM_STDIN
    override val environment = LITERAL

    override fun parse(stdout: ByteArray) = Unit
}
