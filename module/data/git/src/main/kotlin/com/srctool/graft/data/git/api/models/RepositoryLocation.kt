// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.models

import java.nio.file.Path

/**
 * Where a repository is, as git sees it, with symlinks resolved so the same repository reached two ways is one
 * (Repositories and Workspaces, edge cases).
 */
public data class RepositoryLocation(
    /** The top of the working tree; null for a bare repository. */
    val workTree: Path?,
    /** `.git`, or `.git/worktrees/<name>` for a linked worktree. */
    val gitDir: Path,
    /** The shared refs and objects: [gitDir] except in a linked worktree. */
    val commonDir: Path,
) {
    val isBare: Boolean get() = workTree == null
}

/** What the Home screen and a tab show about a repository without loading it (Repositories and Workspaces). */
public data class RepositorySummary(
    val head: Head,
    /** The current branch's upstream, or null when it has none (or HEAD is detached). */
    val upstream: Upstream?,
    /** Staged, unstaged or untracked changes. */
    val dirty: Boolean,
    /** How many paths have them: the graph's WIP row says "Uncommitted changes (4 files)". */
    val changedFiles: Int = 0,
)

/** What HEAD points at. */
public sealed interface Head {
    /** A branch; [commit] is null while the branch has no commits yet (a new repository). */
    public data class Branch(val name: String, val commit: ObjectId?) : Head

    /** No branch: HEAD is a commit. */
    public data class Detached(val commit: ObjectId) : Head
}

/** The branch's upstream and how far apart they are. [gone] when the upstream ref was deleted (`[gone]`). */
public data class Upstream(val name: String, val ahead: Int, val behind: Int, val gone: Boolean = false)

/** What to clone, and how (Repositories and Workspaces, "Clone"). */
public data class CloneRequest(
    val url: String,
    /** The folder to create; its parent is created if missing. */
    val directory: Path,
    /** Shallow clone with this many commits. */
    val depth: Int? = null,
    /** Only [branch] (or the remote's default branch). */
    val singleBranch: Boolean = false,
    val branch: String? = null,
    val recurseSubmodules: Boolean = false,
    /** `--filter=blob:none`: file contents are fetched when needed. */
    val partial: Boolean = false,
)

/** A clone as it runs: git's progress phases, then the cloned repository. */
public sealed interface CloneProgress {
    /** A phase such as "Receiving objects", with its count when git reports one. */
    public data class Phase(val name: String, val percent: Int?, val done: Long?, val total: Long?) : CloneProgress

    public data class Finished(val location: RepositoryLocation) : CloneProgress
}
