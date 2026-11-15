// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.di

import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.data.git.api.repositories.CommitRepository
import com.srctool.graft.data.git.api.repositories.CompareRepository
import com.srctool.graft.data.git.api.repositories.DiffRepository
import com.srctool.graft.data.git.api.repositories.RefRepository
import com.srctool.graft.data.git.api.repositories.ReflogRepository
import com.srctool.graft.data.git.api.repositories.RemoteRepository
import com.srctool.graft.data.git.api.repositories.RepositoryRepository
import com.srctool.graft.data.git.api.repositories.StashRepository
import com.srctool.graft.data.git.implementation.repositories.GitCommitRepository
import com.srctool.graft.data.git.implementation.repositories.GitCompareRepository
import com.srctool.graft.data.git.implementation.repositories.GitDiffRepository
import com.srctool.graft.data.git.implementation.repositories.GitRefRepository
import com.srctool.graft.data.git.implementation.repositories.GitReflogRepository
import com.srctool.graft.data.git.implementation.repositories.GitRemoteRepository
import com.srctool.graft.data.git.implementation.repositories.GitRepositoryRepository
import com.srctool.graft.data.git.implementation.repositories.GitStashRepository

/**
 * The one way other modules get `:module:data:git`'s repositories: `:app:desktop` and `:app:cli` build it with their
 * configured [GitRunner] (hand-wired DI, Architecture note) and hand the repositories on.
 */
public class GitDataModule(runner: GitRunner) {
    public val commits: CommitRepository = GitCommitRepository(runner)
    public val refs: RefRepository = GitRefRepository(runner)
    public val repositories: RepositoryRepository = GitRepositoryRepository(runner)
    public val diffs: DiffRepository = GitDiffRepository(runner)
    public val compare: CompareRepository = GitCompareRepository(runner)
    public val reflogs: ReflogRepository = GitReflogRepository(runner)
    public val stashes: StashRepository = GitStashRepository(runner)
    public val remotes: RemoteRepository = GitRemoteRepository(runner)
}
