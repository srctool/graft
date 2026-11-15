// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.app.api.models

import java.nio.file.Path

/** A value read from one of Graft's files, and what the reader found (Storage Formats 3.1). */
public data class Saved<T>(val value: T, val status: SavedStatus) {
    /** Written by a newer Graft: shown, never written back. */
    val readOnly: Boolean get() = status is SavedStatus.Newer
}

public sealed interface SavedStatus {
    /** No file yet: the defaults. */
    public data object Missing : SavedStatus

    public data object Current : SavedStatus

    /** Saved by a newer Graft in format [version]; changes are refused (`DATA-R-WRIT-VER`). */
    public data class Newer(val version: Int) : SavedStatus

    /** The file was corrupt and was moved to [corruptCopy]; the defaults are used. The UI shows a warning. */
    public data class Recovered(val corruptCopy: Path) : SavedStatus
}
