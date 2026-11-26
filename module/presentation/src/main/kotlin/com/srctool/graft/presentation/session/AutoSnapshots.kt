// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.presentation.session

import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

/**
 * Local History's automatic snapshots for one open repository (Local History, "When a snapshot is taken"). The app runs
 * it for as long as the repository is open.
 */
interface AutoSnapshots {
    /**
     * Applies the retention rules and takes a first snapshot (so what is there now can be got back even if the next
     * change deletes it), then snapshots after each burst of [changes] in the working tree at [repository]. Never
     * returns; cancel it to stop. A failed snapshot is not reported: the next change tries again.
     */
    suspend fun run(repository: Path, changes: Flow<Unit>)
}
