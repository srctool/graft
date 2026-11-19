// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.models

/** The files in a tree: their paths from the repository root, and whether more existed than were kept. */
public data class FileList(val paths: List<String>, val truncated: Boolean)
