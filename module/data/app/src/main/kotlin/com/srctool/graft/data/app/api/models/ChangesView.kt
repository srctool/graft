// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.app.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How the Changes panel groups uncommitted changes (Changelists, "Mode"). */
@Serializable
public enum class ChangesMode {
    /** Staged and Unstaged, over the user's own index. */
    @SerialName("staging")
    STAGING_AREA,

    /** One section per changelist; the index is only used while committing. */
    @SerialName("changelists")
    CHANGELISTS,
}

/** A repository's or a profile's choice of [ChangesMode]; the most specific level that isn't [INHERIT] wins. */
public enum class ChangesView {
    INHERIT,
    STAGING_AREA,
    CHANGELISTS,
    ;

    /** This choice as a mode, or [inherited] when it is [INHERIT]. */
    public fun resolve(inherited: ChangesMode): ChangesMode = when (this) {
        INHERIT -> inherited
        STAGING_AREA -> ChangesMode.STAGING_AREA
        CHANGELISTS -> ChangesMode.CHANGELISTS
    }

    public companion object {
        /** Repository over profile over app. The profile's choice arrives with profiles (G3). */
        public fun effective(repository: ChangesView, profile: ChangesView, app: ChangesMode): ChangesMode =
            repository.resolve(profile.resolve(app))
    }
}
