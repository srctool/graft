// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.domain.api.models

/** One step of loading a graph: more rows, the end, or a failure. Every step carries the [graph] it grew. */
internal sealed interface GraphLoad {
    val graph: LaidOutGraph

    /** A batch was laid out; [rowCount] rows are ready to show. */
    data class Progress(override val graph: LaidOutGraph, val rowCount: Int, val widest: Int) : GraphLoad

    /** Every commit is laid out. */
    data class Loaded(override val graph: LaidOutGraph, val rowCount: Int, val widest: Int) : GraphLoad

    /**
     * `git log` failed after the rows already in [graph]; [error] is Graft's `GitException` or Sinew's
     * `ParseException`, with its error code. Cancellation is never a failure.
     */
    data class Failed(override val graph: LaidOutGraph, val error: Throwable) : GraphLoad
}
