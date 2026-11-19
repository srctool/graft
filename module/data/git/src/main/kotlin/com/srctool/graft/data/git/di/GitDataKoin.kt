// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.di

import com.srctool.graft.data.git.api.repositories.CommitRepository
import com.srctool.graft.data.git.api.repositories.CompareRepository
import com.srctool.graft.data.git.api.repositories.DiffRepository
import com.srctool.graft.data.git.api.repositories.GitInstallRepository
import com.srctool.graft.data.git.api.repositories.IdentityRepository
import com.srctool.graft.data.git.api.repositories.RefRepository
import com.srctool.graft.data.git.api.repositories.ReflogRepository
import com.srctool.graft.data.git.api.repositories.RemoteRepository
import com.srctool.graft.data.git.api.repositories.RepositoryAccessRepository
import com.srctool.graft.data.git.api.repositories.StashRepository
import com.srctool.graft.data.git.api.repositories.TreeRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * This module's repositories for Koin (ADR-28), each bound to its `api` interface. The app's Koin setup provides the
 * `GitRunner`; [GitDataModule] builds the implementations, so tests and the CLI can still wire by hand.
 */
public val gitDataKoin: Module =
    module {
        single { GitDataModule(get()) }
        single<CommitRepository> { get<GitDataModule>().commits }
        single<RefRepository> { get<GitDataModule>().refs }
        single<RepositoryAccessRepository> { get<GitDataModule>().access }
        single<DiffRepository> { get<GitDataModule>().diffs }
        single<CompareRepository> { get<GitDataModule>().compare }
        single<ReflogRepository> { get<GitDataModule>().reflogs }
        single<StashRepository> { get<GitDataModule>().stashes }
        single<RemoteRepository> { get<GitDataModule>().remotes }
        single<IdentityRepository> { get<GitDataModule>().identity }
        single<TreeRepository> { get<GitDataModule>().trees }
        single<GitInstallRepository> { get<GitDataModule>().install }
    }
