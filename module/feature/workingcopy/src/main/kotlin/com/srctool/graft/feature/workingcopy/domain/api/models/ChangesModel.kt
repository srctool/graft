// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain.api.models

import com.srctool.graft.data.git.api.models.ConflictKind
import com.srctool.graft.data.git.api.models.SubmoduleChange
import java.nio.file.Path

/**
 * One working copy in the Changes panel: the repository itself, then (from G5) its submodules (Working Copy and
 * Commit, "Changes panel"). Every use case takes one, so adding sections later changes none of them.
 */
internal data class WorkingCopy(val path: Path, val name: String, val kind: Kind = Kind.REPOSITORY) {
    enum class Kind { REPOSITORY, SUBMODULE }
}

internal enum class RowStatus { MODIFIED, ADDED, DELETED, RENAMED, COPIED, TYPE_CHANGED, UNTRACKED, CONFLICT }

/**
 * A path in a section. [hunks] is which hunks of the file this section holds (null: all of it, the usual case); a
 * changelist can hold some hunks of a file while another list holds the rest, so a file can be in two sections.
 */
internal data class ChangeRow(
    val path: String,
    val status: RowStatus,
    val originalPath: String? = null,
    val conflict: ConflictKind? = null,
    val submodule: SubmoduleChange? = null,
    val modeChanged: Boolean = false,
    val hunks: Set<Int>? = null,
    /** How many hunks the whole file has, so a partial row can say "2 of 3". */
    val hunkCount: Int = 0,
) {
    val partial: Boolean get() = hunks != null && hunks.size < hunkCount

    val name: String get() = path.substringAfterLast('/')
}

internal sealed interface SectionKind {
    data object Conflicts : SectionKind

    data object Staged : SectionKind

    data object Unstaged : SectionKind

    data class Changelist(val id: String, val name: String, val active: Boolean) : SectionKind
}

internal data class ChangesSection(val kind: SectionKind, val rows: List<ChangeRow>)

/** What the Changes panel shows for one working copy, in the order it shows it. */
internal data class ChangesModel(val workingCopy: WorkingCopy, val sections: List<ChangesSection>) {
    val isEmpty: Boolean get() = sections.all { it.rows.isEmpty() }

    /** Every path in any section, once. */
    val paths: Set<String> get() = sections.flatMapTo(LinkedHashSet()) { s -> s.rows.map { it.path } }
}

/** A hunk the changes panel could not keep with its list, for the notice that says so. */
internal data class MovedHunk(val path: String, val fromListId: String)
