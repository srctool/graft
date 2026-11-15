// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.spike

internal sealed interface GraphSpikeEffect {
    /** Scroll [rows] rows and report back with [GraphSpikeEvent.ScrollMeasured]; only the page owns the list. */
    data class RunScrollBenchmark(val rows: Int) : GraphSpikeEffect

    /** Every measurement is in. */
    data class Finished(val results: SpikeResults) : GraphSpikeEffect
}
