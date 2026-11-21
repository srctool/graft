// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.data.git.api.repositories.LocalHistoryRepository
import com.srctool.graft.data.git.implementation.repositories.GitLocalHistoryRepository
import com.srctool.graft.data.git.implementation.repositories.GitStagingRepository
import com.srctool.graft.data.git.implementation.repositories.MutationRecorder

/** A staging repository on the sandbox's runner, recording into the repositories' op logs. */
internal fun GitSandbox.staging(
    history: LocalHistoryRepository = GitLocalHistoryRepository(runner()),
): GitStagingRepository {
    val runner = runner()
    return GitStagingRepository(runner, history, MutationRecorder(runner))
}
