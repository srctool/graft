// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.spike

import com.srctool.graft.feature.graph.domain.api.models.LaidOutGraph

internal data class GraphSpikeState(
    /** The graph being loaded; null until the first batch. */
    val graph: LaidOutGraph? = null,
    /** Rows the page may show; grows as batches arrive, and drives recomposition. */
    val rowCount: Int = 0,
    val widest: Int = 1,
    val phase: Phase = Phase.Idle,
    /** Why `git log` failed (the exception, with Graft's error code in its message). */
    val error: String? = null,
) {
    enum class Phase { Idle, Loading, Loaded, Failed }
}
