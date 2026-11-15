// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.watcher

import java.nio.file.Path

internal enum class ChangeKind { REFS, STATUS, OPERATION }

/**
 * What a changed path means for one repository (Architecture, "Refresh"), or null when it means nothing: objects,
 * lock files (the rename that follows is the real change), Graft's own files, other worktrees. [ignore] hides
 * working-tree paths the caller doesn't want (ignored build folders).
 */
internal class ChangeClassifier(
    private val repo: WatchedRepository,
    private val ignore: (Path) -> Boolean = { false },
) {
    fun classify(path: Path): ChangeKind? {
        if (path.fileName?.toString()?.endsWith(".lock") == true) return null
        // The worktree's own git dir first: in a linked worktree it lives inside the common dir.
        if (path.startsWith(repo.gitDir) && path != repo.gitDir) {
            gitDirKind(repo.gitDir.relativize(path))?.let { return it }
            if (repo.gitDir != repo.commonDir) return null
        }
        if (path.startsWith(repo.commonDir) && path != repo.commonDir) {
            return commonDirKind(repo.commonDir.relativize(path))
        }
        val work = repo.workTree ?: return null
        if (!path.startsWith(work) || path == work) return null
        val relative = work.relativize(path)
        // A linked worktree's `.git` is a file pointing at its git dir, never a change of its own.
        if (relative.getName(0).toString() == ".git") return null
        return if (ignore(path)) null else ChangeKind.STATUS
    }

    /** Whether the watcher should skip [dir] and everything below it: objects, other worktrees, ignored folders. */
    fun skipsFolder(dir: Path): Boolean {
        val heavy =
            listOf(
                repo.gitDir.resolve("objects"),
                repo.commonDir.resolve("objects"),
                repo.commonDir.resolve("worktrees"),
            )
        if (dir in heavy) return true
        val work = repo.workTree
        return work != null && dir.startsWith(work) && !dir.startsWith(repo.gitDir) && ignore(dir)
    }

    private fun gitDirKind(relative: Path): ChangeKind? {
        val first = relative.getName(0).toString()
        return when {
            relative.nameCount == 1 && first in REF_FILES -> ChangeKind.REFS
            relative.nameCount == 1 && first == "index" -> ChangeKind.STATUS
            relative.nameCount == 1 && first in OPERATION_FILES -> ChangeKind.OPERATION
            first in OPERATION_FOLDERS -> ChangeKind.OPERATION
            first == "logs" -> ChangeKind.REFS
            else -> null
        }
    }

    private fun commonDirKind(relative: Path): ChangeKind? {
        val first = relative.getName(0).toString()
        return when {
            first == "refs" || first == "logs" -> ChangeKind.REFS

            relative.nameCount == 1 && (first == "packed-refs" || first == "config") -> ChangeKind.REFS

            // Outside linked worktrees the common dir is the git dir, whose own files were matched above.
            else -> null
        }
    }

    private companion object {
        val REF_FILES = setOf("HEAD", "ORIG_HEAD", "FETCH_HEAD")
        val OPERATION_FILES = setOf("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "BISECT_LOG")
        val OPERATION_FOLDERS = setOf("rebase-merge", "rebase-apply", "sequencer")
    }
}
