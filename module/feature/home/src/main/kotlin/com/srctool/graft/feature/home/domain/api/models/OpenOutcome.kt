// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.domain.api.models

import com.srctool.graft.data.git.api.models.RepositoryLocation
import java.nio.file.Path

/** What opening a folder found (Repositories and Workspaces, "Open / add existing" and its edge cases). */
internal sealed interface OpenOutcome {
    /**
     * The repository [folder] belongs to. [root] is what the tab opens: the working tree's top, or the git folder of
     * a bare repository. [subfolder] is where inside it the user pointed, relative to [root], for the file tree to
     * scroll to; null when it was the root itself.
     */
    data class Opened(val location: RepositoryLocation, val root: Path, val subfolder: Path?) : OpenOutcome

    /** [folder] exists but isn't in a repository: offer **Init** or **Clone** into it. */
    data class NotARepository(val folder: Path) : OpenOutcome

    /** [folder] doesn't exist (any more). */
    data class FolderMissing(val folder: Path) : OpenOutcome

    /**
     * Git refuses the repository because another user owns it ("dubious ownership"). The dialog explains it and shows
     * [command], the exact change **Trust this folder** makes, before making it; [folder] is git's own spelling of the
     * path, which `safe.directory` has to match. Never `*`.
     */
    data class Untrusted(val folder: String, val command: List<String>) : OpenOutcome

    /** Anything else; [error] carries its code and git's output. */
    data class Failed(val error: Throwable) : OpenOutcome
}

/** A repository a folder scan found; [known] when Home already lists it. */
internal data class ScannedRepository(val path: Path, val known: Boolean)
