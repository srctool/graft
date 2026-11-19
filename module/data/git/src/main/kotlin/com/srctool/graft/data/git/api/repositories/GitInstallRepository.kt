// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.repositories

import com.srctool.graft.data.git.api.models.GitInstallStatus

/** Whether git can be started, and is new enough (Git Engine note: 2.38). */
public interface GitInstallRepository {
    /** Runs `git --version`; every outcome, including a missing or old git, is a [GitInstallStatus]. */
    public suspend fun check(): GitInstallStatus
}
