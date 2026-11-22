// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.app.api.models

/** One named group of uncommitted changes, with its commit message draft (Changelists, "Model"). */
public data class Changelist(
    val id: String,
    val name: String,
    val description: String = "",
    /** The commit message typed for this list and not committed yet; kept when the mode is switched away and back. */
    val draft: String = "",
)

/**
 * What identifies a hunk apart from where it sits: the hashes `:module:data:git` makes of its lines, and its place in
 * the old side. Stored as given and handed back to be matched against the working tree again.
 */
public data class StoredHunk(
    val oldStart: Int,
    val oldCount: Int,
    val change: String,
    val removed: String,
    val base: String,
    val removedCount: Int,
)

/** A hunk of a modified file that belongs to [listId]. */
public data class HunkAssignment(val path: String, val listId: String, val hunk: StoredHunk)

/** A path that moves as a whole (a new file, a deletion, a binary file, a pure rename) and belongs to [listId]. */
public data class FileAssignment(val path: String, val listId: String)

/** A hunk the working tree has now, with the list that owns it, or null when it is a new change. */
public data class CurrentHunk(val path: String, val hunk: StoredHunk, val owner: String?)

/** A path the working tree has now that has no hunks, and the path it was renamed from. */
public data class CurrentFile(val path: String, val originalPath: String? = null)

/** Why [ChangelistBook.nameProblem] refuses a name. */
public enum class ChangelistNameProblem { EMPTY, DUPLICATE }

/**
 * Every changelist of one worktree, `$GIT_DIR/graft/changelists.json` v1. A **Default** list always exists, one list
 * is **active** (new changes land there), and each change belongs to exactly one list. Every operation returns a new
 * book and leaves the old one alone; a list id that doesn't exist is an error (`IllegalArgumentException`), so check
 * with [nameProblem] and [find] first.
 */
public data class ChangelistBook(
    val lists: List<Changelist> = listOf(DEFAULT),
    val activeId: String = DEFAULT_ID,
    val files: List<FileAssignment> = emptyList(),
    val hunks: List<HunkAssignment> = emptyList(),
) {
    /** The list with [id], or null. */
    public fun find(id: String): Changelist? = lists.firstOrNull { it.id == id }

    val active: Changelist get() = find(activeId) ?: lists.first()

    /** Whether [name] can name a list, ignoring [except] (the list being renamed). Names are unique, ignoring case. */
    public fun nameProblem(name: String, except: String? = null): ChangelistNameProblem? = when {
        name.isBlank() -> ChangelistNameProblem.EMPTY

        lists.any {
            it.id != except && it.name.equals(name.trim(), ignoreCase = true)
        } -> ChangelistNameProblem.DUPLICATE

        else -> null
    }

    public fun create(id: String, name: String, description: String = ""): ChangelistBook {
        require(find(id) == null) { "list $id exists" }
        require(nameProblem(name) == null) { "bad list name" }
        return copy(lists = lists + Changelist(id, name.trim(), description))
    }

    public fun rename(id: String, name: String): ChangelistBook {
        requireNotNull(find(id)) { "no list $id" }
        require(id != DEFAULT_ID) { "the Default list keeps its name" }
        require(nameProblem(name, except = id) == null) { "bad list name" }
        return update(id) { it.copy(name = name.trim()) }
    }

    public fun describe(id: String, description: String): ChangelistBook = update(id) {
        it.copy(description = description)
    }

    public fun withDraft(id: String, draft: String): ChangelistBook = update(id) { it.copy(draft = draft) }

    public fun activate(id: String): ChangelistBook {
        requireNotNull(find(id)) { "no list $id" }
        return copy(activeId = id)
    }

    /** Removes a list; its changes go to Default, and Default becomes active if this list was. */
    public fun delete(id: String): ChangelistBook {
        requireNotNull(find(id)) { "no list $id" }
        require(id != DEFAULT_ID) { "the Default list can't be deleted" }
        return copy(
            lists = lists.filter { it.id != id },
            activeId = if (activeId == id) DEFAULT_ID else activeId,
            files = files.map { if (it.listId == id) it.copy(listId = DEFAULT_ID) else it },
            hunks = hunks.map { if (it.listId == id) it.copy(listId = DEFAULT_ID) else it },
        )
    }

    /** Moves a whole path, and any hunks it has, to [listId]. */
    public fun moveFile(path: String, listId: String): ChangelistBook {
        requireNotNull(find(listId)) { "no list $listId" }
        return copy(
            files = files.map { if (it.path == path) it.copy(listId = listId) else it },
            hunks = hunks.map { if (it.path == path) it.copy(listId = listId) else it },
        )
    }

    /** Moves one hunk to [listId]; [hunk] is matched as stored (the same lines at the same place). */
    public fun moveHunk(path: String, hunk: StoredHunk, listId: String): ChangelistBook {
        requireNotNull(find(listId)) { "no list $listId" }
        var moved = false
        return copy(
            hunks =
            hunks.map {
                if (!moved && it.path == path && it.hunk == hunk) {
                    moved = true
                    it.copy(listId = listId)
                } else {
                    it
                }
            },
        )
    }

    /**
     * Brings the hunk assignments in line with the working tree: each [current] hunk is stored under its owner, or
     * under the active list when it has none, and a hunk that is no longer in the working tree is forgotten. The
     * stored lines are the current ones, so the next match is exact. An owner that is not a list falls back to Default.
     */
    public fun withHunks(current: List<CurrentHunk>): ChangelistBook = copy(
        hunks =
        current.map {
            HunkAssignment(it.path, it.owner?.takeIf { id -> find(id) != null } ?: activeId, it.hunk)
        },
    )

    /**
     * Brings the whole-path assignments in line with the working tree: a path keeps its list, follows its rename
     * (`originalPath`), or lands in the active list; a path that is no longer changed is forgotten.
     */
    public fun withFiles(current: List<CurrentFile>): ChangelistBook = copy(
        files =
        current.map { file ->
            val owner =
                files.firstOrNull { it.path == file.path }?.listId
                    ?: files.firstOrNull { it.path == file.originalPath }?.listId
                    ?: activeId
            FileAssignment(file.path, owner)
        },
    )

    /**
     * The switch from the staging area: what was staged becomes a list called [name] (made with [id] if none has that
     * name yet) and everything else stays where it is. [stagedHunks] and [stagedFiles] are what the index held.
     */
    public fun withStaged(
        id: String,
        name: String,
        stagedHunks: List<CurrentHunk>,
        stagedFiles: List<String>,
    ): ChangelistBook {
        if (stagedHunks.isEmpty() && stagedFiles.isEmpty()) return this
        val existing = lists.firstOrNull { it.name.equals(name, ignoreCase = true) }
        val book = if (existing == null) create(id, name) else this
        val target = existing?.id ?: id
        return book.copy(
            hunks = book.hunks.filter { h -> stagedHunks.none { it.path == h.path && it.hunk == h.hunk } } +
                stagedHunks.map { HunkAssignment(it.path, target, it.hunk) },
            files = book.files.filter { it.path !in stagedFiles } + stagedFiles.map { FileAssignment(it, target) },
        )
    }

    /** The paths and hunks that [listId] owns, for the commit, shelve and discard of one list. */
    public fun ownedBy(listId: String): Owned = Owned(
        files.filter { it.listId == listId }.map { it.path }.toSet(),
        hunks.filter { it.listId == listId },
    )

    public data class Owned(val files: Set<String>, val hunks: List<HunkAssignment>)

    private fun update(id: String, change: (Changelist) -> Changelist): ChangelistBook {
        requireNotNull(find(id)) { "no list $id" }
        return copy(lists = lists.map { if (it.id == id) change(it) else it })
    }

    public companion object {
        public const val DEFAULT_ID: String = "default"
        public val DEFAULT: Changelist = Changelist(DEFAULT_ID, "Default")
    }
}
