// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.presentation.history

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The sentences the view model puts in toasts and the status line, and how it writes a snapshot's time. */
internal interface HistoryTexts {
    /** The time of day, "14:02". */
    fun time(seconds: Long): String

    fun restoring(): String

    fun snapshotting(): String

    fun removing(): String

    fun restoredFile(name: String, time: String): String

    fun restoredAll(time: String, removed: Int): String

    fun unprotected(path: String): String

    fun undone(): String

    fun changedSince(path: String?): String

    fun snapshotTaken(): String

    fun nothingChanged(time: String): String

    fun purged(name: String, snapshots: Int): String

    open class English(private val zone: ZoneId = ZoneId.systemDefault()) : HistoryTexts {
        override fun time(seconds: Long): String = CLOCK.format(Instant.ofEpochSecond(seconds).atZone(zone))

        override fun restoring() = "Restoring…"

        override fun snapshotting() = "Taking a snapshot…"

        override fun removing() = "Removing from Local History…"

        override fun restoredFile(name: String, time: String) = "Restored $name to $time."

        override fun restoredAll(time: String, removed: Int) = "Restored to $time." +
            when (removed) {
                0 -> ""
                1 -> " 1 newer file removed."
                else -> " $removed newer files removed."
            }

        override fun unprotected(path: String) =
            "Nothing restored: Local History can't hold $path as it is now (ignored, or too large)."

        override fun undone() = "Restore undone."

        override fun changedSince(path: String?) =
            (path?.let { "$it changed since." } ?: "The files changed since.") + " Restore from Local History instead."

        override fun snapshotTaken() = "Snapshot taken."

        override fun nothingChanged(time: String) = "Nothing changed since $time."

        override fun purged(name: String, snapshots: Int) =
            "Removed $name from ${if (snapshots == 1) "1 snapshot" else "$snapshots snapshots"}."

        private companion object {
            val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        }
    }
}
