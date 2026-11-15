// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.repositories

import com.srctool.graft.data.git.api.models.GlobalIdentity
import com.srctool.graft.data.git.api.models.IdentityWrite
import com.srctool.sinew.models.Result

/** The global git identity onboarding previews, edits and sets (G1 decision D5; G3 moves it into profiles). */
public interface IdentityRepository {
    public suspend fun read(): Result<GlobalIdentity>

    /**
     * Sets `user.name` and `user.email` with `git config --global`, only the ones whose value differs from what git
     * reads now; nothing is written when both are unchanged. A value that's blank, has a line break, or starts with
     * `-` (git would read it as an option) is refused (`GIT-R-IDNT-…`, a validation error naming the field).
     */
    public suspend fun write(name: String, email: String): Result<IdentityWrite>
}
