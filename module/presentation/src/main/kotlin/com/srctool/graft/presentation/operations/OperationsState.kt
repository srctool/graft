// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.presentation.operations

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/** The kinds of background work G1 runs; the status bar words each one (UX Writing). */
enum class OperationKind {
    CLONE,
    SCAN,
    LOAD_GRAPH,
    SEARCH,
    REFRESH,
}

/**
 * Work running in the background: what it is, the repository or folder it's about, and how far along it is when it
 * can tell ([percent] 0–100).
 */
data class RunningOperation(val id: Long, val kind: OperationKind, val target: Path?, val percent: Int? = null)

/** The status bar's list of running work (Architecture, `:module:presentation`). */
class OperationsState {
    private val ids = AtomicLong()
    private val state = MutableStateFlow<List<RunningOperation>>(emptyList())

    /** Oldest first. */
    val running: StateFlow<List<RunningOperation>> = state.asStateFlow()

    /** Shows [kind] as running until [block] ends, however it ends; [block] can report its progress. */
    suspend fun <T> track(kind: OperationKind, target: Path? = null, block: suspend (Progress) -> T): T {
        val operation = RunningOperation(ids.incrementAndGet(), kind, target)
        state.update { it + operation }
        try {
            return block(Progress { percent -> update(operation.id, percent) })
        } finally {
            state.update { list -> list.filterNot { it.id == operation.id } }
        }
    }

    private fun update(id: Long, percent: Int?) {
        val clamped = percent?.coerceIn(0, 100)
        state.update { list -> list.map { if (it.id == id) it.copy(percent = clamped) else it } }
    }

    /** Reports how far a tracked operation is; null when it can't tell. */
    fun interface Progress {
        fun report(percent: Int?)
    }
}
