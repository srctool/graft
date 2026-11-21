// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.di

import com.srctool.graft.core.git.CommandLog
import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.data.git.api.repositories.CommitRepository
import com.srctool.graft.data.git.api.repositories.CompareRepository
import com.srctool.graft.data.git.api.repositories.DiffRepository
import com.srctool.graft.data.git.api.repositories.GitInstallRepository
import com.srctool.graft.data.git.api.repositories.GitLogRepository
import com.srctool.graft.data.git.api.repositories.IdentityRepository
import com.srctool.graft.data.git.api.repositories.RefRepository
import com.srctool.graft.data.git.api.repositories.ReflogRepository
import com.srctool.graft.data.git.api.repositories.RemoteRepository
import com.srctool.graft.data.git.api.repositories.RepositoryAccessRepository
import com.srctool.graft.data.git.api.repositories.StashRepository
import com.srctool.graft.data.git.api.repositories.TreeRepository
import com.srctool.graft.data.git.implementation.repositories.GitCommitRepository
import com.srctool.graft.data.git.implementation.repositories.GitCompareRepository
import com.srctool.graft.data.git.implementation.repositories.GitDiffRepository
import com.srctool.graft.data.git.implementation.repositories.GitIdentityRepository
import com.srctool.graft.data.git.implementation.repositories.GitInstallRepositoryImpl
import com.srctool.graft.data.git.implementation.repositories.GitRefRepository
import com.srctool.graft.data.git.implementation.repositories.GitReflogRepository
import com.srctool.graft.data.git.implementation.repositories.GitRemoteRepository
import com.srctool.graft.data.git.implementation.repositories.GitRepositoryAccessRepository
import com.srctool.graft.data.git.implementation.repositories.GitStashRepository
import com.srctool.graft.data.git.implementation.repositories.GitTreeRepository
import com.srctool.graft.data.git.implementation.repositories.RecordedGitLogRepository
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The one way other modules get `:module:data:git`'s repositories: `:app:desktop` and `:app:cli` build it with their
 * configured [GitRunner] (hand-wired DI, Architecture note) and hand the repositories on. Commands that need no
 * repository (the global identity) run in [neutralFolder], a folder no repository's config applies to. [commandLog]
 * is the one the runner records into, for the Git log.
 */
public class GitDataModule(
    runner: GitRunner,
    neutralFolder: Path = Paths.get(System.getProperty("java.io.tmpdir")),
    commandLog: CommandLog = CommandLog(),
) {
    public val commits: CommitRepository = GitCommitRepository(runner)
    public val refs: RefRepository = GitRefRepository(runner)
    public val access: RepositoryAccessRepository = GitRepositoryAccessRepository(runner)
    public val diffs: DiffRepository = GitDiffRepository(runner)
    public val compare: CompareRepository = GitCompareRepository(runner)
    public val reflogs: ReflogRepository = GitReflogRepository(runner)
    public val stashes: StashRepository = GitStashRepository(runner)
    public val remotes: RemoteRepository = GitRemoteRepository(runner)
    public val identity: IdentityRepository = GitIdentityRepository(runner, neutralFolder)
    public val trees: TreeRepository = GitTreeRepository(runner)
    public val install: GitInstallRepository = GitInstallRepositoryImpl(runner, neutralFolder)
    public val log: GitLogRepository = RecordedGitLogRepository(commandLog)
}
