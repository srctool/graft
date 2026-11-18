// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.branches

internal sealed interface BranchesEffect {
    data class CopyToClipboard(val text: String) : BranchesEffect
}
