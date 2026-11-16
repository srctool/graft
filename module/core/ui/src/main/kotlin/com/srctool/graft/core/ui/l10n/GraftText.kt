// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.l10n

import androidx.compose.runtime.Composable
import com.srctool.graft.core.ui.theme.Graft

/** Graft's text for [key] in the current language, or [fallback] (English) with [args] filled in. */
@Composable
public fun graftText(key: String, fallback: String, vararg args: Pair<String, Any>): String {
    val arguments = args.toMap()
    return Graft.localizer.resolve(key, arguments) ?: GraftLocalizer.format(fallback, arguments)
}
