// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.repositories

import com.srctool.graft.data.git.api.models.Allowances
import com.srctool.graft.data.git.api.models.GuardFinding
import com.srctool.graft.data.git.api.models.GuardOptions
import com.srctool.graft.data.git.api.models.GuardReport
import com.srctool.graft.data.git.api.models.GuardTarget
import com.srctool.sinew.models.Result
import java.nio.file.Path

/**
 * Commit Guards: local checks for secrets, sensitive files, large files, conflict markers and case collisions. Rules
 * are bundled and run on this machine; content never leaves it. Nothing here returns a secret's value (T12).
 */
public interface GuardRepository {
    /**
     * Scans [target] (`GIT-R-GARD-…`). The diff is read as git writes it, so a push of hundreds of commits is one pass
     * with memory that doesn't grow with the diff. Findings the allow-lists cover are in [GuardReport.allowed].
     */
    public suspend fun scan(
        repository: Path,
        target: GuardTarget,
        options: GuardOptions = GuardOptions(),
    ): Result<GuardReport>

    /** The allow-lists now: local fingerprints and the shared `.graft/guard.toml`. */
    public suspend fun allowances(repository: Path): Result<Allowances>

    /** "Allow once": the user said go on for this finding. It is written to the op log and allowed nowhere else. */
    public suspend fun allowOnce(repository: Path, finding: GuardFinding): Result<Unit>

    /**
     * "Allow always for this finding": stores its fingerprint in `$GIT_DIR/graft/guard-allow.json`, or, when [shared],
     * in the committed `.graft/guard.toml` for the team. Refused for a file saved by a newer Graft.
     */
    public suspend fun allowAlways(repository: Path, finding: GuardFinding, shared: Boolean = false): Result<Unit>

    /** Allows files by path pattern in `.graft/guard.toml` (test fixtures with fake keys). */
    public suspend fun allowPaths(repository: Path, patterns: List<String>): Result<Unit>
}
