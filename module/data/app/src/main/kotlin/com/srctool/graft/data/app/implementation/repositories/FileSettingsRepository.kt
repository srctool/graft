// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.app.implementation.repositories

import com.srctool.graft.data.app.api.models.AppSettings
import com.srctool.graft.data.app.api.models.Saved
import com.srctool.graft.data.app.api.repositories.SettingsRepository
import com.srctool.graft.data.app.implementation.source.local.JsonStore
import com.srctool.sinew.models.Result

internal class FileSettingsRepository(private val store: JsonStore<AppSettings>) : SettingsRepository {
    override suspend fun read(): Result<Saved<AppSettings>> = store.read()

    override suspend fun update(change: (AppSettings) -> AppSettings): Result<AppSettings> = store.update(change)
}
