// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.spike

internal sealed interface GraphSpikeEvent {
    /** The page is shown. */
    data object Opened : GraphSpikeEvent
}
