// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.models

import java.nio.file.Path

/** The git Graft runs: its [version] (such as `2.54.0`), and the first `git` on `PATH` when there is one. */
public data class GitInstall(val version: String, val location: Path?)

/** What checking git found (Onboarding's first step). */
public sealed interface GitInstallStatus {
    public data class Ready(val install: GitInstall) : GitInstallStatus

    /** git runs but is older than [required]. */
    public data class TooOld(val found: String, val required: String) : GitInstallStatus

    /** git can't be started: not installed, or not on `PATH`. */
    public data object Missing : GitInstallStatus

    /** git started but its answer wasn't understood, or the check failed otherwise. */
    public data class Unreadable(val error: Throwable) : GitInstallStatus
}
