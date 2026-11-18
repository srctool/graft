// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.compare

import com.srctool.graft.data.git.api.models.ChangedFile
import com.srctool.graft.data.git.api.models.CommitRow
import com.srctool.graft.data.git.api.models.MergePreview
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.feature.graph.domain.api.models.CompareSummary
import com.srctool.graft.feature.graph.presentation.details.FileRow

/** Which diff the files show (Compare Branches, "Views"). */
internal enum class CompareKind {
    /** `git diff A...B`: from the merge base, what a pull request shows. The default. */
    CHANGES,

    /** `git diff A B`: the trees as they are now. */
    DIRECT,
}

/** A commit in one of Compare's lists, with its author's name. */
internal data class CompareCommit(val row: CommitRow, val author: String)

/** Which picker is open. */
internal enum class Side { A, B }

/** Compare (S7, Task 15 wireframe). */
internal data class CompareState(
    /** A (base) and B (compared), as the center names them: full ref names or commit ids. */
    val a: String? = null,
    val b: String? = null,
    val phase: Phase = Phase.Picking,
    val summary: CompareSummary? = null,
    /** Null while it loads or when it doesn't apply (B is 0 ahead). */
    val preview: MergePreview? = null,
    val previewLoading: Boolean = false,
    val kind: CompareKind = CompareKind.CHANGES,
    /** Commits in B and not in A, then in A and not in B, each up to [LIST_LIMIT]. */
    val onlyInB: List<CompareCommit> = emptyList(),
    val onlyInA: List<CompareCommit> = emptyList(),
    val files: List<ChangedFile> = emptyList(),
    val filesLoading: Boolean = false,
    val tree: Boolean = true,
    val collapsed: Set<String> = emptySet(),
    val fileRows: List<FileRow> = emptyList(),
    /** The focused row: `commit:<id>` or a file row's key. */
    val focus: String? = null,
    /** The revision git couldn't resolve, for "Couldn't find …". */
    val missing: String? = null,
    val error: Throwable? = null,
    val outputShown: Boolean = false,
    val picker: Side? = null,
    val query: String = "",
    /** What a typed query resolved to, offered as "Commit …". */
    val typed: ObjectId? = null,
    /** The refs the pickers list: full names. */
    val refs: List<String> = emptyList(),
    val now: Long = 0,
) {
    enum class Phase { Picking, Loading, Ready, Same, NotFound, Failed }

    /** The commits' rows then the files' rows, in Tab order, for ↑/↓. */
    val rowKeys: List<String>
        get() = (onlyInB + onlyInA).map { "commit:${it.row.id.hex}" } + fileRows.map { it.key }

    companion object {
        /** Each list stops here, with the count of the rest (Task 15, question 3). */
        const val LIST_LIMIT: Int = 1_000
    }
}

internal sealed interface CompareEvent {
    data object Opened : CompareEvent

    data object Back : CompareEvent

    data object Swapped : CompareEvent

    data class PickerOpened(val side: Side?) : CompareEvent

    data class QueryTyped(val query: String) : CompareEvent

    /** A picker's choice: a full ref name or a commit id. */
    data class Picked(val side: Side, val revision: String) : CompareEvent

    data class KindChosen(val kind: CompareKind) : CompareEvent

    data class TreeChosen(val tree: Boolean) : CompareEvent

    data class Focused(val key: String) : CompareEvent

    data class Moved(val by: Int) : CompareEvent

    data object Expand : CompareEvent

    data object Collapse : CompareEvent

    data class Activated(val key: String) : CompareEvent

    data object Retry : CompareEvent

    data class OutputShown(val shown: Boolean) : CompareEvent
}

internal sealed interface CompareAction {
    data object Start : CompareAction

    class Local(val change: (CompareState) -> CompareState) : CompareAction

    data class Show(val a: String?, val b: String?) : CompareAction

    data class Query(val query: String) : CompareAction

    data class Kind(val kind: CompareKind) : CompareAction

    data class Activate(val key: String) : CompareAction

    data class Fold(val open: Boolean) : CompareAction

    data object Back : CompareAction

    data object Reload : CompareAction
}
