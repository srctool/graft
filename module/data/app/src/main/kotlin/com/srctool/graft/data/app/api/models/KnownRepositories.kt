// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.app.api.models

import java.nio.file.Path

/**
 * A repository Home lists: recently opened or pinned. [path] is the real path (symlinks resolved), which is what
 * duplicates are matched on. [missing] when the folder is gone or no longer holds a repository (Home greys it out,
 * with **Locate…** and **Remove**).
 */
public data class KnownRepository(
    val path: Path,
    /** Seconds since the epoch. */
    val lastOpened: Long,
    val pinned: Boolean,
    val missing: Boolean,
)

/**
 * A window's saved place on screen and its repository tabs; the app state keeps one per open window, the main window
 * first. [tabs] are the repositories' folders in tab order (Home is always there and isn't saved) and [activeTab] is
 * the one in front: an index into [tabs], or -1 for Home.
 */
public data class SavedWindow(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val maximized: Boolean,
    val tabs: List<String> = emptyList(),
    val activeTab: Int = -1,
)

/** A named group of repositories (Repositories and Workspaces). G1 has local workspaces only. */
public data class Workspace(val id: String, val name: String, val repositories: List<Path>)
