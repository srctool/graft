// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.graph

import com.srctool.graft.data.git.api.models.ObjectId

/** One-time effects for the page and its host. */
internal sealed interface GraphEffect {
    /** Enter on a commit: the host opens its details (the right panel, Task 14). */
    data class OpenCommit(val id: ObjectId) : GraphEffect

    data class CopyToClipboard(val text: String) : GraphEffect
}
