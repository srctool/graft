// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.repositories

import com.srctool.graft.core.git.GitIgnoreRefusedException
import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.core.git.IgnoreProblem
import com.srctool.graft.data.git.api.models.CleanEntry
import com.srctool.graft.data.git.api.models.CleanPreview
import com.srctool.graft.data.git.api.models.CleanResult
import com.srctool.graft.data.git.api.models.CleanScope
import com.srctool.graft.data.git.api.models.LocalHistoryLimits
import com.srctool.graft.data.git.api.repositories.CleanRepository
import com.srctool.graft.data.git.api.repositories.LocalHistoryRepository
import com.srctool.graft.data.git.implementation.source.local.CleanListCommand
import com.srctool.graft.data.git.implementation.source.local.CleanRunCommand
import com.srctool.graft.data.git.implementation.source.local.IgnoredFilesCommand
import com.srctool.graft.data.git.implementation.source.local.InsideRepositoryCommand
import com.srctool.sinew.models.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal class GitCleanRepository(
    private val runner: GitRunner,
    private val history: LocalHistoryRepository,
    private val recorder: MutationRecorder,
    private val limits: LocalHistoryLimits = LocalHistoryLimits(),
) : CleanRepository {
    override suspend fun preview(repository: Path, scope: CleanScope): Result<CleanPreview> {
        (runner.run(InsideRepositoryCommand(), repository) as? Result.Failure)?.let { return it }
        val listed =
            when (val r = runner.run(CleanListCommand(scope, nested = false), repository)) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        val withNested =
            when (val r = runner.run(CleanListCommand(scope, nested = true), repository)) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        val nested = withNested.toSet() - listed.toSet()
        // What no snapshot can hold: ignored files (a snapshot leaves them out), told apart only when they can be there.
        val ignored =
            if (scope == CleanScope.UNTRACKED) {
                emptySet()
            } else {
                when (val r = runner.run(IgnoredFilesCommand(), repository)) {
                    is Result.Success -> r.data
                    is Result.Failure -> return r
                }
            }
        val root = repository.toAbsolutePath().normalize()
        val entries =
            withContext(Dispatchers.IO) {
                (listed + nested.sorted()).map { measure(root, it, it in nested, ignored) }
            }
        return Result.Success(
            CleanPreview(
                scope,
                entries.sortedWith(
                    compareByDescending<CleanEntry> {
                        it.bytes
                    }.thenBy { it.path },
                ),
            ),
        )
    }

    override suspend fun clean(
        repository: Path,
        scope: CleanScope,
        paths: List<String>,
        confirmedNested: Set<String>,
    ): Result<CleanResult> {
        val current =
            when (val r = preview(repository, scope)) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        val byPath = current.entries.associateBy { it.path }
        val selected = ArrayList<CleanEntry>()
        for (path in paths.distinct()) {
            // A folder is listed as `dir/`; naming it without the slash means the same.
            val entry =
                byPath[path] ?: byPath["$path/"]
                    ?: return Result.Failure(GitIgnoreRefusedException("CLEN", IgnoreProblem.NOT_IN_PREVIEW, path))
            if (entry.nestedRepository && entry.path !in confirmedNested && path !in confirmedNested) {
                return Result.Failure(GitIgnoreRefusedException("CLEN", IgnoreProblem.NESTED_REPOSITORY, entry.path))
            }
            selected += entry
        }
        if (selected.isEmpty()) return Result.Success(CleanResult(emptyList(), null, 0))

        // The way back, taken once the selection is known to fit and before anything is removed (E2). If it can't be
        // taken, nothing is removed.
        val snapshot =
            when (val r = history.snapshot(repository, "before clean")) {
                is Result.Success -> r.data.snapshot
                is Result.Failure -> return r
            }
        val plain = selected.filter { !it.nestedRepository }.map { it.path }
        val nested = selected.filter { it.nestedRepository }.map { it.path }
        for (chunk in chunks(plain)) {
            (recorder.run(CleanRunCommand(scope, nested = false, paths = chunk), repository) as? Result.Failure)?.let {
                return it
            }
        }
        for (chunk in chunks(nested)) {
            (recorder.run(CleanRunCommand(scope, nested = true, paths = chunk), repository) as? Result.Failure)?.let {
                return it
            }
        }
        return Result.Success(CleanResult(selected.map { it.path }, snapshot, selected.sumOf { it.lostBytes }))
    }

    /** Keeps each command line well under Windows' 32,767 characters. */
    private fun chunks(paths: List<String>): List<List<String>> {
        val out = ArrayList<List<String>>()
        var current = ArrayList<String>()
        var size = 0
        for (path in paths) {
            if (current.isNotEmpty() && size + path.length + 1 > ARGUMENT_BUDGET) {
                out += current
                current = ArrayList()
                size = 0
            }
            current += path
            size += path.length + 1
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    /** The size of [path] and the part of it no snapshot holds; links are counted as links and never followed. */
    private fun measure(root: Path, path: String, nested: Boolean, ignored: Set<String>): CleanEntry {
        val target = root.resolve(path.trimEnd('/'))
        var bytes = 0L
        var lost = 0L
        try {
            Files.walkFileTree(
                target,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val size = if (attrs.isRegularFile) attrs.size() else 0L
                        bytes += size
                        val relative = root.relativize(file).joinToString("/")
                        if (nested || relative in ignored || size > limits.maxFileBytes) lost += size
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                        FileVisitResult.CONTINUE
                },
            )
        } catch (_: IOException) {
            // A file that vanished or can't be read counts as nothing; clean itself will report real problems.
        }
        return CleanEntry(path, path.endsWith("/"), bytes, lost, nested)
    }

    private companion object {
        const val ARGUMENT_BUDGET = 8_000
    }
}
