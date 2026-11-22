// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.di

import com.srctool.graft.core.git.CommandLog
import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.data.git.api.repositories.CommitRepository
import com.srctool.graft.data.git.api.repositories.CommittingRepository
import com.srctool.graft.data.git.api.repositories.CompareRepository
import com.srctool.graft.data.git.api.repositories.DiffRepository
import com.srctool.graft.data.git.api.repositories.GitInstallRepository
import com.srctool.graft.data.git.api.repositories.GitLogRepository
import com.srctool.graft.data.git.api.repositories.IdentityRepository
import com.srctool.graft.data.git.api.repositories.LocalHistoryRepository
import com.srctool.graft.data.git.api.repositories.OperationLogRepository
import com.srctool.graft.data.git.api.repositories.RefRepository
import com.srctool.graft.data.git.api.repositories.ReflogRepository
import com.srctool.graft.data.git.api.repositories.RemoteRepository
import com.srctool.graft.data.git.api.repositories.RepositoryAccessRepository
import com.srctool.graft.data.git.api.repositories.StagingRepository
import com.srctool.graft.data.git.api.repositories.StashRepository
import com.srctool.graft.data.git.api.repositories.TreeRepository
import com.srctool.graft.data.git.api.repositories.WorkingTreeRepository
import com.srctool.graft.data.git.implementation.repositories.GitCommitRepository
import com.srctool.graft.data.git.implementation.repositories.GitCommittingRepository
import com.srctool.graft.data.git.implementation.repositories.GitCompareRepository
import com.srctool.graft.data.git.implementation.repositories.GitDiffRepository
import com.srctool.graft.data.git.implementation.repositories.GitIdentityRepository
import com.srctool.graft.data.git.implementation.repositories.GitInstallRepositoryImpl
import com.srctool.graft.data.git.implementation.repositories.GitLocalHistoryRepository
import com.srctool.graft.data.git.implementation.repositories.GitOperationLogRepository
import com.srctool.graft.data.git.implementation.repositories.GitRefRepository
import com.srctool.graft.data.git.implementation.repositories.GitReflogRepository
import com.srctool.graft.data.git.implementation.repositories.GitRemoteRepository
import com.srctool.graft.data.git.implementation.repositories.GitRepositoryAccessRepository
import com.srctool.graft.data.git.implementation.repositories.GitStagingRepository
import com.srctool.graft.data.git.implementation.repositories.GitStashRepository
import com.srctool.graft.data.git.implementation.repositories.GitTreeRepository
import com.srctool.graft.data.git.implementation.repositories.GitWorkingTreeRepository
import com.srctool.graft.data.git.implementation.repositories.MutationRecorder
import com.srctool.graft.data.git.implementation.repositories.RecordedGitLogRepository
import com.srctool.graft.data.git.implementation.source.local.OpLogStore
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
    public val workingTree: WorkingTreeRepository = GitWorkingTreeRepository(runner)
    public val history: LocalHistoryRepository = GitLocalHistoryRepository(runner)
    private val opLogStore = OpLogStore()
    private val recorder = MutationRecorder(runner, opLogStore)
    public val operations: OperationLogRepository = GitOperationLogRepository(recorder, opLogStore)
    private val stagingImpl = GitStagingRepository(runner, history, recorder)
    public val staging: StagingRepository = stagingImpl
    public val committing: CommittingRepository = GitCommittingRepository(runner, stagingImpl, recorder)
}
