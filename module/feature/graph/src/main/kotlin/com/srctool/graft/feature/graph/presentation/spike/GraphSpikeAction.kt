// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.spike

/** The work an event asks for; see [GraphSpikeViewModel.mapEventToAction]. */
internal sealed interface GraphSpikeAction {
    data object Load : GraphSpikeAction

    data class RecordFirstScreen(val nanos: Long) : GraphSpikeAction

    data class RecordScroll(val results: ScrollResults) : GraphSpikeAction
}
