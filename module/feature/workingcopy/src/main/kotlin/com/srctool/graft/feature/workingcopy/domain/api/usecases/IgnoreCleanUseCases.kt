// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain.api.usecases

import com.srctool.graft.data.git.api.models.CleanEntry
import com.srctool.graft.data.git.api.models.CleanPreview
import com.srctool.graft.data.git.api.models.CleanResult
import com.srctool.graft.data.git.api.models.CleanScope
import com.srctool.graft.data.git.api.models.IgnoreExplanation
import com.srctool.graft.data.git.api.models.IgnorePreview
import com.srctool.graft.data.git.api.models.IgnoreTarget
import com.srctool.graft.data.git.api.models.IgnoreTemplate
import com.srctool.graft.data.git.api.models.IgnoreWrite
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingCopy
import com.srctool.sinew.models.Result
import java.nio.file.Path

/** The patterns the "Ignore" menu offers for a file or folder. */
internal enum class IgnoreChoice { THIS_FILE, EXTENSION, THIS_FOLDER, CUSTOM }

/**
 * What an ignore would do, shown before it is written: the [patterns], the [file] they go in, what they match now
 * ([preview]), and which of those files git already tracks, for which ignoring does nothing ([alreadyTracked]).
 */
internal data class IgnorePlan(
    val target: IgnoreTarget,
    val file: Path,
    val patterns: List<String>,
    val preview: IgnorePreview,
) {
    val alreadyTracked: List<String> get() = preview.tracked
}

/** Ignore from the Changes panel and the `.gitignore` editor (Ignore and Clean). */
internal interface IgnoreUseCase {
    /**
     * Builds the plan for [path] (relative to the working copy). [folder] says the path is a folder. A `.gitignore`
     * target holds patterns relative to its own folder, so [path] must be under it. [custom] is the text of
     * [IgnoreChoice.CUSTOM]. Nothing is written.
     */
    suspend fun plan(
        workingCopy: WorkingCopy,
        path: String,
        choice: IgnoreChoice,
        target: IgnoreTarget,
        custom: String = "",
        folder: Boolean = false,
    ): Result<IgnorePlan>

    /** The live preview of typed [patterns] for the editor, as for a `.gitignore` in [base]. */
    suspend fun preview(workingCopy: WorkingCopy, patterns: List<String>, base: String = ""): Result<IgnorePreview>

    /**
     * Writes the plan's patterns. With [untrack], the tracked files they match are taken out of the index afterwards
     * (`git rm --cached`) and stay on disk; if that fails the patterns are already written.
     */
    suspend fun apply(workingCopy: WorkingCopy, plan: IgnorePlan, untrack: Boolean = false): Result<IgnoreWrite>

    /** "Why is this ignored?": the rule, or null when nothing ignores [path]. */
    suspend fun explain(workingCopy: WorkingCopy, path: String): Result<IgnoreExplanation?>

    fun templates(): List<IgnoreTemplate>

    /** Merges the template called [name] into [target] without repeating a line. */
    suspend fun addTemplate(workingCopy: WorkingCopy, target: IgnoreTarget, name: String): Result<IgnoreWrite>
}

/**
 * The files ticked in the clean dialog. Everything but nested repositories starts ticked; a nested repository only
 * counts once the user ticks it themself.
 */
internal class CleanTicks private constructor(val preview: CleanPreview, private val ticked: Set<String>) {
    fun isTicked(path: String): Boolean = path in ticked

    fun toggle(path: String): CleanTicks =
        if (preview.entries.none { it.path == path }) this else CleanTicks(preview, ticked.symmetricDifference(path))

    fun tickAll(): CleanTicks = CleanTicks(
        preview,
        preview.entries.filter {
            !it.nestedRepository
        }.map { it.path }.toSet(),
    )

    fun clear(): CleanTicks = CleanTicks(preview, emptySet())

    val entries: List<CleanEntry> get() = preview.entries.filter { it.path in ticked }

    val paths: List<String> get() = entries.map { it.path }

    val bytes: Long get() = entries.sumOf { it.bytes }

    /** What no snapshot can bring back: the dialog warns when this isn't zero. */
    val lostBytes: Long get() = entries.sumOf { it.lostBytes }

    /** Ticked nested repositories, which `clean` removes only when named. */
    val nested: Set<String> get() = entries.filter { it.nestedRepository }.map { it.path }.toSet()

    private fun Set<String>.symmetricDifference(path: String): Set<String> = if (path in
        this
    ) {
        this - path
    } else {
        this + path
    }

    companion object {
        fun of(preview: CleanPreview): CleanTicks = CleanTicks(preview, emptySet()).tickAll()
    }
}

/** Clean, always previewed first (Ignore and Clean, "Clean"). */
internal interface CleanUseCase {
    suspend fun preview(workingCopy: WorkingCopy, scope: CleanScope): Result<CleanPreview>

    /** Removes what [ticks] has ticked, after the data layer's snapshot (`GitIgnoreRefusedException` when stale). */
    suspend fun clean(workingCopy: WorkingCopy, ticks: CleanTicks): Result<CleanResult>
}
