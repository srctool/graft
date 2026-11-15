// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.spike

/*
 * G0 Task 8: the throwaway graph spike that checks ADR-1 (Compose Desktop can draw a 500k-commit graph at 60 fps in
 * 400 MB). Deleted or rewritten in G1.
 */

/** What the spike measured; printed by the app and shown in the page's header. */
public data class SpikeResults(
    val rows: Int,
    val widestLanes: Int,
    /** From `main()`: includes starting the JVM's Compose window. */
    val firstScreenMillis: Long,
    /** From the moment the graph starts reading the repository (the window is already up). */
    val openToFirstScreenMillis: Long,
    val fullLoadMillis: Long,
    val heapAfterLoadMb: Long,
    val scroll: ScrollResults?,
)

public data class ScrollResults(
    val rowsScrolled: Int,
    val frames: Int,
    val averageFps: Double,
    val p99FrameMillis: Double,
    val maxFrameMillis: Double,
    val framesOver32Millis: Int,
    val framesOver16Millis: Int,
)
