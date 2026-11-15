// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.app.implementation.source.local

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** File-system checks on repository folders; git itself confirms a repository when it's opened. */
internal object RepositoryFolders {
    /** [path] with symlinks resolved, or made absolute and normalized when it doesn't exist (any more). */
    fun real(path: Path): Path = try {
        path.toRealPath()
    } catch (_: IOException) {
        path.toAbsolutePath().normalize()
    }

    /** A `.git` folder or file (worktrees and submodules have a file), or a bare repository's own layout. */
    fun looksLikeRepository(folder: Path): Boolean =
        Files.exists(folder.resolve(".git"), LinkOption.NOFOLLOW_LINKS) || isBare(folder)

    private fun isBare(folder: Path): Boolean = Files.isRegularFile(folder.resolve("HEAD")) &&
        Files.isDirectory(folder.resolve("objects")) &&
        Files.isDirectory(folder.resolve("refs"))
}
