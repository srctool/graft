// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain.implementation.usecases

import com.srctool.graft.data.git.api.models.CleanPreview
import com.srctool.graft.data.git.api.models.CleanResult
import com.srctool.graft.data.git.api.models.CleanScope
import com.srctool.graft.data.git.api.models.IgnoreExplanation
import com.srctool.graft.data.git.api.models.IgnorePatterns
import com.srctool.graft.data.git.api.models.IgnorePreview
import com.srctool.graft.data.git.api.models.IgnoreTarget
import com.srctool.graft.data.git.api.models.IgnoreTemplate
import com.srctool.graft.data.git.api.models.IgnoreWrite
import com.srctool.graft.data.git.api.repositories.CleanRepository
import com.srctool.graft.data.git.api.repositories.IgnoreRepository
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingCopy
import com.srctool.graft.feature.workingcopy.domain.api.usecases.CleanTicks
import com.srctool.graft.feature.workingcopy.domain.api.usecases.CleanUseCase
import com.srctool.graft.feature.workingcopy.domain.api.usecases.IgnoreChoice
import com.srctool.graft.feature.workingcopy.domain.api.usecases.IgnorePlan
import com.srctool.graft.feature.workingcopy.domain.api.usecases.IgnoreUseCase
import com.srctool.sinew.models.Result

internal class GitIgnoreUseCase(private val ignore: IgnoreRepository) : IgnoreUseCase {
    override suspend fun plan(
        workingCopy: WorkingCopy,
        path: String,
        choice: IgnoreChoice,
        target: IgnoreTarget,
        custom: String,
        folder: Boolean,
    ): Result<IgnorePlan> {
        val base = (target as? IgnoreTarget.Gitignore)?.directory.orEmpty()
        val pattern =
            when (choice) {
                IgnoreChoice.THIS_FILE -> IgnorePatterns.file(path, base)

                IgnoreChoice.EXTENSION -> IgnorePatterns.extension(path)

                IgnoreChoice.THIS_FOLDER ->
                    (if (folder) path else path.substringBeforeLast('/', "")).trim('/').ifEmpty { null }
                        ?.let { IgnorePatterns.folder(it, base) }

                IgnoreChoice.CUSTOM -> custom.trim().ifEmpty { null }
            }
        // Nothing to offer: a name with no extension, a file in the root asked for "this folder", an empty custom line.
        val patterns = listOfNotNull(pattern)
        val file =
            when (val r = ignore.file(workingCopy.path, target)) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        val preview =
            when (val r = ignore.preview(workingCopy.path, patterns, base)) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        return Result.Success(IgnorePlan(target, file, patterns, preview))
    }

    override suspend fun preview(
        workingCopy: WorkingCopy,
        patterns: List<String>,
        base: String,
    ): Result<IgnorePreview> = ignore.preview(workingCopy.path, patterns, base)

    override suspend fun apply(workingCopy: WorkingCopy, plan: IgnorePlan, untrack: Boolean): Result<IgnoreWrite> {
        val written =
            when (val r = ignore.add(workingCopy.path, plan.target, plan.patterns)) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        if (untrack && plan.alreadyTracked.isNotEmpty()) {
            (ignore.untrack(workingCopy.path, plan.alreadyTracked) as? Result.Failure)?.let { return it }
        }
        return Result.Success(written)
    }

    override suspend fun explain(workingCopy: WorkingCopy, path: String): Result<IgnoreExplanation?> =
        when (val r = ignore.explain(workingCopy.path, listOf(path))) {
            is Result.Success -> Result.Success(r.data.firstOrNull())
            is Result.Failure -> r
        }

    override fun templates(): List<IgnoreTemplate> = ignore.templates()

    override suspend fun addTemplate(
        workingCopy: WorkingCopy,
        target: IgnoreTarget,
        name: String,
    ): Result<IgnoreWrite> {
        val template = ignore.templates().firstOrNull { it.name == name }
        // An unknown name adds nothing: the picker only offers names from templates().
        val lines = template?.let { listOf("# ${it.name}") + it.lines }.orEmpty()
        return ignore.add(workingCopy.path, target, lines)
    }
}

internal class GitCleanUseCase(private val clean: CleanRepository) : CleanUseCase {
    override suspend fun preview(workingCopy: WorkingCopy, scope: CleanScope): Result<CleanPreview> =
        clean.preview(workingCopy.path, scope)

    override suspend fun clean(workingCopy: WorkingCopy, ticks: CleanTicks): Result<CleanResult> =
        clean.clean(workingCopy.path, ticks.preview.scope, ticks.paths, ticks.nested)
}
