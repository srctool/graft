// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.source.local

import com.srctool.graft.core.git.GitCommand

/**
 * Where git keeps the commit-graph file and the chain of split ones, as paths it resolves itself
 * (`rev-parse --git-path`): they follow `GIT_OBJECT_DIRECTORY` and a worktree's common directory, which a hand-built
 * `.git/objects/...` path would miss. Relative paths are relative to the repository folder git ran in.
 */
internal class CommitGraphPathsCommand : GitCommand<List<String>>() {
    override val function = "CGPH"
    override val args =
        listOf(
            "rev-parse",
            "--git-path",
            "objects/info/commit-graph",
            "--git-path",
            "objects/info/commit-graphs/commit-graph-chain",
        )

    override fun parse(stdout: ByteArray): List<String> = GitText.decode(stdout).lines().filter { it.isNotEmpty() }
}

/** `git commit-graph write --reachable`: what D8's "Speed up the graph" runs, once, when the author asks. */
internal class CommitGraphWriteCommand : GitCommand<Unit>() {
    override val function = "CGPH"
    override val mutates = true
    override val args = listOf("commit-graph", "write", "--reachable", "--no-progress")

    override fun parse(stdout: ByteArray) = Unit
}
