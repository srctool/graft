// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.app.api.repositories

import com.srctool.graft.data.app.api.models.AppSettings
import com.srctool.graft.data.app.api.models.Saved
import com.srctool.sinew.models.Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** `settings.json` in the app data folder. */
public interface SettingsRepository {
    /**
     * The settings as last read or written, null until the first [read]; every successful [read] and [update]
     * publishes here, so the window re-themes the moment Settings changes a value.
     */
    public val changes: StateFlow<AppSettings?> get() = Unpublished

    public suspend fun read(): Result<Saved<AppSettings>>

    /**
     * Re-reads the file, applies [change] and writes the result, so a change made elsewhere since the last read isn't
     * lost. Refused for a file saved by a newer Graft.
     */
    public suspend fun update(change: (AppSettings) -> AppSettings): Result<AppSettings>
}

private val Unpublished: StateFlow<AppSettings?> = MutableStateFlow(null)
