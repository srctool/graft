// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.spike

internal sealed interface GraphSpikeEvent {
    /** The page is shown. */
    data object Opened : GraphSpikeEvent

    /** The page drew a frame with the first screen of rows, at [nanos] (`System.nanoTime()`). */
    data class FirstScreenShown(val nanos: Long) : GraphSpikeEvent

    /** The page finished the scroll benchmark it was asked to run. */
    data class ScrollMeasured(val results: ScrollResults) : GraphSpikeEvent
}
