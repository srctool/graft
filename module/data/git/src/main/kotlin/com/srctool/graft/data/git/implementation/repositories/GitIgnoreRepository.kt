// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.repositories

import com.srctool.graft.core.git.GitIgnoreRefusedException
import com.srctool.graft.core.git.GitNotFoundException
import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.core.git.IgnoreProblem
import com.srctool.graft.data.git.api.models.IgnoreExplanation
import com.srctool.graft.data.git.api.models.IgnorePreview
import com.srctool.graft.data.git.api.models.IgnoreTarget
import com.srctool.graft.data.git.api.models.IgnoreTemplate
import com.srctool.graft.data.git.api.models.IgnoreWarning
import com.srctool.graft.data.git.api.models.IgnoreWrite
import com.srctool.graft.data.git.api.repositories.IgnoreRepository
import com.srctool.graft.data.git.implementation.source.local.BundledTemplates
import com.srctool.graft.data.git.implementation.source.local.CheckIgnoreCommand
import com.srctool.graft.data.git.implementation.source.local.GitPathCommand
import com.srctool.graft.data.git.implementation.source.local.GitText
import com.srctool.graft.data.git.implementation.source.local.GlobalExcludesCommand
import com.srctool.graft.data.git.implementation.source.local.IgnoreText
import com.srctool.graft.data.git.implementation.source.local.IgnoredByFileCommand
import com.srctool.graft.data.git.implementation.source.local.InsideRepositoryCommand
import com.srctool.graft.data.git.implementation.source.local.RemoveCachedCommand
import com.srctool.graft.data.git.implementation.source.local.TopLevelCommand
import com.srctool.graft.data.git.implementation.source.local.pathspecInput
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.SinewException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

internal class GitIgnoreRepository(
    private val runner: GitRunner,
    private val recorder: MutationRecorder,
    /** Where git reads global excludes when `core.excludesFile` is unset; no config entry is needed for it. */
    private val defaultGlobalExcludes: Path,
) : IgnoreRepository {
    override suspend fun file(repository: Path, target: IgnoreTarget): Result<Path> {
        (runner.run(InsideRepositoryCommand(), repository) as? Result.Failure)?.let { return it }
        return when (target) {
            is IgnoreTarget.Gitignore -> gitignoreFile(repository, target.directory)
            IgnoreTarget.InfoExclude -> runner.run(GitPathCommand("info/exclude"), repository)
            IgnoreTarget.GlobalExcludes -> globalFile(repository)
        }
    }

    private suspend fun gitignoreFile(repository: Path, directory: String): Result<Path> {
        val top =
            when (val r = runner.run(TopLevelCommand(), repository)) {
                is Result.Success -> r.data.normalize()
                is Result.Failure -> return r
            }
        val parts = directory.split('/').filter { it.isNotEmpty() }
        if (directory.startsWith("/") || parts.any { it == ".." || it == "." } || parts.firstOrNull() == ".git") {
            return Result.Failure(GitIgnoreRefusedException("IGNR", IgnoreProblem.UNSAFE_INPUT, directory))
        }
        val file = parts.fold(top) { path, part -> path.resolve(part) }.resolve(".gitignore").normalize()
        return Result.Success(file)
    }

    private suspend fun globalFile(repository: Path): Result<Path> {
        val configured =
            when (val r = runner.run(GlobalExcludesCommand(), repository)) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        val path = configured?.let(Paths::get) ?: return Result.Success(defaultGlobalExcludes)
        return Result.Success(if (path.isAbsolute) path else repository.toAbsolutePath().resolve(path).normalize())
    }

    override suspend fun preview(repository: Path, patterns: List<String>, base: String): Result<IgnorePreview> {
        (runner.run(InsideRepositoryCommand(), repository) as? Result.Failure)?.let { return it }
        unsafe(patterns)?.let { return Result.Failure(it) }
        val gitDir =
            recorder.location(repository)?.gitDir
                ?: return Result.Failure(
                    GitNotFoundException("IGNR", listOf("rev-parse"), 128, "fatal: not a git repository"),
                )
        val rebased = patterns.map { IgnoreText.forBase(it, base) }
        val scratch = gitDir.resolve("graft").resolve("ignore-preview-${UUID.randomUUID()}.txt")
        val (untracked, tracked) =
            try {
                withContext(Dispatchers.IO) {
                    Files.createDirectories(scratch.parent)
                    Files.write(scratch, rebased.filterNotNull().joinToString("\n", postfix = "\n").toByteArray())
                }
                val loose =
                    when (val r = runner.run(IgnoredByFileCommand(scratch, cached = false), repository)) {
                        is Result.Success -> r.data
                        is Result.Failure -> return r
                    }
                val known =
                    when (val r = runner.run(IgnoredByFileCommand(scratch, cached = true), repository)) {
                        is Result.Success -> r.data
                        is Result.Failure -> return r
                    }
                loose to known
            } catch (e: IOException) {
                return Result.Failure(
                    GitIgnoreRefusedException("IGNR", IgnoreProblem.UNSAFE_INPUT, e.message.orEmpty()),
                )
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { Files.deleteIfExists(scratch) }
            }
        val warnings = IgnoreText.parentWarnings(patterns, rebased).toMutableList()
        warnings += existingRuleWarnings(repository, patterns, rebased, warnings)
        val limit = IgnorePreview.LIMIT
        return Result.Success(
            IgnorePreview(
                untracked = untracked.take(limit),
                tracked = tracked.take(limit),
                warnings = warnings,
                truncated = untracked.size > limit || tracked.size > limit,
            ),
        )
    }

    /** Negations whose folder an ignore rule already in the repository hides (the typed lines were checked in text). */
    private suspend fun existingRuleWarnings(
        repository: Path,
        original: List<String>,
        rebased: List<String?>,
        found: List<IgnoreWarning>,
    ): List<IgnoreWarning> {
        val already = found.filterIsInstance<IgnoreWarning.ParentIgnored>().map { it.pattern }.toSet()
        val wanted = LinkedHashMap<String, MutableList<String>>()
        for ((i, line) in rebased.withIndex()) {
            line ?: continue
            if (original[i] in already) continue
            for (parent in IgnoreText.parentsOfNegation(line)) wanted.getOrPut(parent) { ArrayList() } += original[i]
        }
        if (wanted.isEmpty()) return emptyList()
        // A folder is asked as `name/` so that git treats the path as a folder that doesn't exist yet.
        val asked = wanted.keys.map { "$it/" }
        val answers =
            (runner.run(CheckIgnoreCommand(), repository, pathspecInput(asked)) as? Result.Success)?.data
                ?: return emptyList()
        val hidden = answers.filter { !it.negated }.map { it.path.trimEnd('/') }.toSet()
        val out = ArrayList<IgnoreWarning>()
        val warned = HashSet<String>()
        for (parent in wanted.keys) {
            if (parent !in hidden) continue
            for (pattern in wanted.getValue(parent)) {
                if (warned.add(pattern)) {
                    out +=
                        IgnoreWarning.ParentIgnored(pattern, parent)
                }
            }
        }
        return out
    }

    override suspend fun add(repository: Path, target: IgnoreTarget, patterns: List<String>): Result<IgnoreWrite> {
        unsafe(patterns)?.let { return Result.Failure(it) }
        val file =
            when (val r = file(repository, target)) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        return withContext(Dispatchers.IO) {
            try {
                val created = !Files.exists(file)
                val bytes = if (created) ByteArray(0) else Files.readAllBytes(file)
                val text = GitText.decode(bytes)
                val eol = if (text.contains("\r\n")) "\r\n" else "\n"
                val merged = IgnoreText.merge(text.split('\n'), patterns, eol)
                if (merged.text.isEmpty()) {
                    return@withContext Result.Success(IgnoreWrite(file, emptyList(), merged.skipped, created = false))
                }
                // Existing bytes stay as they are, whatever their encoding; only a missing final newline is added.
                val joiner = if (bytes.isNotEmpty() &&
                    bytes.last() != '\n'.code.toByte()
                ) {
                    eol.toByteArray()
                } else {
                    ByteArray(0)
                }
                IgnoreText.writeAtomic(file, bytes + joiner + merged.text.toByteArray(Charsets.UTF_8))
                Result.Success(IgnoreWrite(file, merged.added, merged.skipped, created))
            } catch (e: IOException) {
                Result.Failure(GitIgnoreRefusedException("IGNR", IgnoreProblem.UNSAFE_INPUT, "$file: ${e.message}"))
            }
        }
    }

    override suspend fun explain(repository: Path, paths: List<String>): Result<List<IgnoreExplanation>> {
        if (paths.isEmpty()) return Result.Success(emptyList())
        (runner.run(InsideRepositoryCommand(), repository) as? Result.Failure)?.let { return it }
        return runner.run(CheckIgnoreCommand(), repository, pathspecInput(paths))
    }

    override suspend fun untrack(repository: Path, paths: List<String>): Result<Unit> {
        if (paths.isEmpty()) return Result.Success(Unit)
        (runner.run(InsideRepositoryCommand(), repository) as? Result.Failure)?.let { return it }
        return recorder.run(RemoveCachedCommand(), repository, input = pathspecInput(paths))
    }

    override fun templates(): List<IgnoreTemplate> = BundledTemplates.all

    override fun templatesLicense(): String = BundledTemplates.license

    /** A pattern with a line break would be two lines in the file: refused rather than split. */
    private fun unsafe(patterns: List<String>): SinewException? =
        patterns.firstOrNull { '\n' in it || '\r' in it || '\u0000' in it }?.let {
            GitIgnoreRefusedException("IGNR", IgnoreProblem.UNSAFE_INPUT, "a pattern with a line break")
        }
}
