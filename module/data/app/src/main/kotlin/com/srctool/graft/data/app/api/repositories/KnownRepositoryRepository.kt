// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.app.api.repositories

import com.srctool.graft.data.app.api.models.KnownRepository
import com.srctool.graft.data.app.api.models.Saved
import com.srctool.graft.data.app.api.models.SavedWindow
import com.srctool.sinew.models.Result
import java.nio.file.Path

/** Recent and pinned repositories, kept in the app state file. Paths are matched by their real path. */
public interface KnownRepositoryRepository {
    /** Pinned first, then by when they were last opened, newest first; each checked for [KnownRepository.missing]. */
    public suspend fun list(): Result<Saved<List<KnownRepository>>>

    /** Records that [path] (a repository's root) was just opened. The oldest unpinned entries drop off the list. */
    public suspend fun opened(path: Path): Result<Unit>

    public suspend fun setPinned(path: Path, pinned: Boolean): Result<Unit>

    public suspend fun remove(path: Path): Result<Unit>

    /** **Locate…**: the repository at [from] now lives at [to]; it keeps its place and pin. */
    public suspend fun relocate(from: Path, to: Path): Result<Unit>
}

/** Window places, kept in the app state file and restored at start (clamped to the screens there are then). */
public interface WindowStateRepository {
    public suspend fun windows(): Result<List<SavedWindow>>

    public suspend fun save(windows: List<SavedWindow>): Result<Unit>
}
