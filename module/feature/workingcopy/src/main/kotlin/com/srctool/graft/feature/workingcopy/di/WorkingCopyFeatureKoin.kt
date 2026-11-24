// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.di

import com.srctool.graft.feature.workingcopy.domain.api.usecases.BeforeRiskyOperation
import com.srctool.graft.feature.workingcopy.domain.api.usecases.CleanUseCase
import com.srctool.graft.feature.workingcopy.domain.api.usecases.CommitChangesUseCase
import com.srctool.graft.feature.workingcopy.domain.api.usecases.DraftsUseCase
import com.srctool.graft.feature.workingcopy.domain.api.usecases.IgnoreUseCase
import com.srctool.graft.feature.workingcopy.domain.api.usecases.LoadChangesUseCase
import com.srctool.graft.feature.workingcopy.domain.api.usecases.LocalHistoryUseCase
import com.srctool.graft.feature.workingcopy.domain.api.usecases.PanelStateUseCase
import com.srctool.graft.feature.workingcopy.domain.api.usecases.UndoRedoUseCase
import com.srctool.graft.feature.workingcopy.domain.implementation.usecases.FileDraftsUseCase
import com.srctool.graft.feature.workingcopy.domain.implementation.usecases.FilePanelStateUseCase
import com.srctool.graft.feature.workingcopy.domain.implementation.usecases.GitCleanUseCase
import com.srctool.graft.feature.workingcopy.domain.implementation.usecases.GitIgnoreUseCase
import com.srctool.graft.feature.workingcopy.domain.implementation.usecases.GitLoadChangesUseCase
import com.srctool.graft.feature.workingcopy.domain.implementation.usecases.GuardedCommitUseCase
import com.srctool.graft.feature.workingcopy.domain.implementation.usecases.LoggedUndoRedoUseCase
import com.srctool.graft.feature.workingcopy.domain.implementation.usecases.SnapshotUseCase
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The working-copy feature for Koin (ADR-28): its use cases. The data modules' Koin modules provide the repositories.
 * The snapshot scheduler is not here: the app session owns its scope and clock and builds it.
 */
public fun workingCopyFeatureKoin(): Module = module {
    factory<LoadChangesUseCase> { GitLoadChangesUseCase(get(), get(), get(), get()) }
    factory<PanelStateUseCase> { FilePanelStateUseCase(get(), get()) }
    factory<DraftsUseCase> { FileDraftsUseCase(get(), get(), get()) }
    factory<CommitChangesUseCase> { GuardedCommitUseCase(get(), get(), get()) }
    factory<UndoRedoUseCase> { LoggedUndoRedoUseCase(get()) }
    factory { SnapshotUseCase(get()) }
    factory<LocalHistoryUseCase> { get<SnapshotUseCase>() }
    factory<BeforeRiskyOperation> { get<SnapshotUseCase>() }
    factory<IgnoreUseCase> { GitIgnoreUseCase(get()) }
    factory<CleanUseCase> { GitCleanUseCase(get()) }
}
