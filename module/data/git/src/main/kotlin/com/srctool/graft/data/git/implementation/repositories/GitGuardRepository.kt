// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.repositories

import com.srctool.graft.core.git.GitGuardRefusedException
import com.srctool.graft.core.git.GitNotFoundException
import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.core.git.GuardProblem
import com.srctool.graft.core.git.TempIndex
import com.srctool.graft.data.git.api.models.Allowances
import com.srctool.graft.data.git.api.models.GuardFinding
import com.srctool.graft.data.git.api.models.GuardOptions
import com.srctool.graft.data.git.api.models.GuardReport
import com.srctool.graft.data.git.api.models.GuardRule
import com.srctool.graft.data.git.api.models.GuardSeverity
import com.srctool.graft.data.git.api.models.GuardTarget
import com.srctool.graft.data.git.api.repositories.GuardRepository
import com.srctool.graft.data.git.implementation.source.local.AllowFile
import com.srctool.graft.data.git.implementation.source.local.GuardAllowJson
import com.srctool.graft.data.git.implementation.source.local.GuardFilesCommand
import com.srctool.graft.data.git.implementation.source.local.GuardPatchCommand
import com.srctool.graft.data.git.implementation.source.local.GuardRules
import com.srctool.graft.data.git.implementation.source.local.GuardToml
import com.srctool.graft.data.git.implementation.source.local.InsideRepositoryCommand
import com.srctool.graft.data.git.implementation.source.local.ObjectSizesCommand
import com.srctool.graft.data.git.implementation.source.local.RawChange
import com.srctool.graft.data.git.implementation.source.local.ReadHeadTreeCommand
import com.srctool.graft.data.git.implementation.source.local.ResolveCommand
import com.srctool.graft.data.git.implementation.source.local.TopLevelCommand
import com.srctool.graft.data.git.implementation.source.local.TreePathsCommand
import com.srctool.graft.data.git.implementation.source.local.guardPatchArgs
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.SinewException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path

internal class GitGuardRepository(
    private val runner: GitRunner,
    private val staging: GitStagingRepository,
    private val recorder: MutationRecorder,
) : GuardRepository {
    override suspend fun scan(repository: Path, target: GuardTarget, options: GuardOptions): Result<GuardReport> {
        (runner.run(InsideRepositoryCommand(), repository) as? Result.Failure)?.let { return it }
        val allow =
            when (val r = allowances(repository)) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        val found =
            when (target) {
                is GuardTarget.Staged -> scanSource(repository, target, options, emptyMap(), "HEAD")
                is GuardTarget.Range -> scanSource(repository, target, options, emptyMap(), target.to)
                is GuardTarget.Selection -> scanSelection(repository, target, options)
            }
        val findings =
            when (found) {
                is Result.Success -> found.data
                is Result.Failure -> return found
            }
        val (allowed, open) = findings.distinctBy { listOf(it.fingerprint, it.line) }.partition(allow::allows)
        return Result.Success(GuardReport(open.sortedWith(ORDER), allowed.sortedWith(ORDER)))
    }

    private suspend fun scanSelection(
        repository: Path,
        target: GuardTarget.Selection,
        options: GuardOptions,
    ): Result<List<GuardFinding>> {
        val gitDir =
            recorder.location(repository)?.gitDir
                ?: return Result.Failure(
                    GitNotFoundException("GARD", listOf("rev-parse"), 128, "fatal: not a git repository"),
                )
        return TempIndex.scope(gitDir) { scratch ->
            val unborn = (runner.run(ResolveCommand("HEAD"), repository) as? Result.Success)?.data == null
            (runner.run(ReadHeadTreeCommand(unborn, scratch.environment), repository) as? Result.Failure)
                ?.let { return@scope it }
            (
                staging.stageInto(
                    repository,
                    target.selection,
                    target.contextLines,
                    scratch.environment,
                ) as? Result.Failure
                )
                ?.let { return@scope it }
            scanSource(repository, target, options, scratch.environment, "HEAD")
        }
    }

    /** The checks on one diff: its added lines, then its files. [tree] is the revision the result is compared with. */
    private suspend fun scanSource(
        repository: Path,
        target: GuardTarget,
        options: GuardOptions,
        index: Map<String, String?>,
        tree: String,
    ): Result<List<GuardFinding>> {
        val findings = ArrayList<GuardFinding>()
        val rules = options.rules
        if (GuardRule.SECRET in rules || GuardRule.CONFLICT_MARKER in rules) {
            val command =
                GuardPatchCommand(
                    guardPatchArgs(target),
                    index,
                    secrets = GuardRule.SECRET in rules,
                    markers = GuardRule.CONFLICT_MARKER in rules,
                )
            try {
                // The flow reads git's output as it is written; only the findings are kept.
                findings += runner.stream(command, repository).toList()
            } catch (e: SinewException) {
                return Result.Failure(e)
            }
        }
        val needFiles = rules.any {
            it == GuardRule.SENSITIVE_FILE || it == GuardRule.LARGE_FILE ||
                it == GuardRule.CASE_COLLISION
        }
        if (!needFiles) return Result.Success(findings)
        val changes =
            when (val r = runner.run(GuardFilesCommand(target, index), repository)) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        val live = changes.filter { it.status != 'D' }
        if (GuardRule.SENSITIVE_FILE in rules) findings += sensitive(live)
        if (GuardRule.LARGE_FILE in rules) {
            when (val r = large(repository, live, options)) {
                is Result.Success -> findings += r.data
                is Result.Failure -> return r
            }
        }
        if (GuardRule.CASE_COLLISION in rules) {
            when (val r = collisions(repository, tree, changes)) {
                is Result.Success -> findings += r.data
                is Result.Failure -> return r
            }
        }
        return Result.Success(findings)
    }

    private fun sensitive(changes: List<RawChange>): List<GuardFinding> = changes.mapNotNull { change ->
        val (label, severity) = GuardRules.sensitiveName(change.path) ?: return@mapNotNull null
        GuardRules.finding(
            GuardRule.SENSITIVE_FILE,
            severity,
            change.path,
            null,
            change.path.substringAfterLast('/'),
            label,
            "",
        )
    }

    private suspend fun large(
        repository: Path,
        changes: List<RawChange>,
        options: GuardOptions,
    ): Result<List<GuardFinding>> {
        // A submodule's "object" is a commit in another repository; it has no size here.
        val files = changes.filter { it.newMode != SUBMODULE && it.newObject.any { c -> c != '0' } }
        if (files.isEmpty()) return Result.Success(emptyList())
        val input = files.joinToString("") { it.newObject + "\n" }.toByteArray()
        val sizes =
            when (val r = runner.run(ObjectSizesCommand(), repository, input)) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        return Result.Success(
            files.mapNotNull { change ->
                val size = sizes[change.newObject] ?: return@mapNotNull null
                val severity =
                    when {
                        size > options.blockBytes -> GuardSeverity.BLOCK
                        size > options.warnBytes -> GuardSeverity.WARN
                        else -> return@mapNotNull null
                    }
                GuardRules.finding(
                    GuardRule.LARGE_FILE,
                    severity,
                    change.path,
                    null,
                    "${size / MEBI} MB",
                    "Large file",
                    "",
                )
            },
        )
    }

    /** Paths that differ only in case, among the ones the change adds and what the tree already has. */
    private suspend fun collisions(
        repository: Path,
        tree: String,
        changes: List<RawChange>,
    ): Result<List<GuardFinding>> {
        val added = changes.filter { it.status == 'A' || it.status == 'R' || it.status == 'C' }
        if (added.isEmpty()) return Result.Success(emptyList())
        val existing =
            if ((runner.run(ResolveCommand(tree), repository) as? Result.Success)?.data == null) {
                emptyList()
            } else {
                when (val r = runner.run(TreePathsCommand(tree), repository)) {
                    is Result.Success -> r.data
                    is Result.Failure -> return r
                }
            }
        // A range's tree already has what the change adds; a staged change's tree is `HEAD`, which doesn't.
        val gone = changes.filter { it.status == 'D' || it.status == 'R' }.map { it.oldPath ?: it.path }.toSet()
        val all = (existing.filter { it !in gone } + added.map { it.path }).distinct()
        val byFolded = all.groupBy { it.lowercase() }.filterValues { it.size > 1 }
        return Result.Success(
            added.mapNotNull { change ->
                val others = byFolded[change.path.lowercase()]?.filter { it != change.path } ?: return@mapNotNull null
                GuardRules.finding(
                    GuardRule.CASE_COLLISION,
                    GuardSeverity.WARN,
                    change.path,
                    null,
                    "also ${others.first()}",
                    "Case collision",
                    (others + change.path).sorted().joinToString("\u0000"),
                )
            },
        )
    }

    override suspend fun allowances(repository: Path): Result<Allowances> {
        val where = place(repository) ?: return Result.Failure(notRepository())
        val local = GuardAllowJson.read(GuardAllowJson.file(where.first))
        val shared = GuardToml.read(GuardToml.file(where.second))
        return Result.Success(
            Allowances(
                local.value.map { it.fingerprint }.toSet() + shared.value.fingerprints,
                shared.value.pathPatterns,
            ),
        )
    }

    override suspend fun allowOnce(repository: Path, finding: GuardFinding): Result<Unit> {
        if (recorder.location(repository) == null) return Result.Failure(notRepository())
        // The op log keeps the rule and the fingerprint, never the excerpt or the value.
        recorder.note(repository, listOf("graft", "guard", "allow-once", finding.rule.name, finding.fingerprint))
        return Result.Success(Unit)
    }

    override suspend fun allowAlways(repository: Path, finding: GuardFinding, shared: Boolean): Result<Unit> {
        val where = place(repository) ?: return Result.Failure(notRepository())
        return if (shared) {
            updateShared(where.second) { it.copy(fingerprints = it.fingerprints + finding.fingerprint) }
        } else {
            val file = GuardAllowJson.file(where.first)
            withContext(Dispatchers.IO) {
                val current = GuardAllowJson.read(file)
                refusal(current, file)?.let { return@withContext Result.Failure(it) }
                if (current.value.any { it.fingerprint == finding.fingerprint }) return@withContext Result.Success(Unit)
                val entry =
                    GuardAllowJson.Entry(
                        finding.fingerprint,
                        finding.rule.name,
                        finding.path,
                        System.currentTimeMillis(),
                    )
                write { GuardAllowJson.write(file, current.value + entry) }
            }
        }
    }

    override suspend fun allowPaths(repository: Path, patterns: List<String>): Result<Unit> {
        val where = place(repository) ?: return Result.Failure(notRepository())
        return updateShared(where.second) { it.copy(pathPatterns = (it.pathPatterns + patterns).distinct()) }
    }

    private suspend fun updateShared(workTree: Path, change: (Allowances) -> Allowances): Result<Unit> {
        val file = GuardToml.file(workTree)
        return withContext(Dispatchers.IO) {
            val current = GuardToml.read(file)
            refusal(current, file)?.let { return@withContext Result.Failure(it) }
            val next = change(current.value)
            if (next == current.value) return@withContext Result.Success(Unit)
            write { GuardToml.write(file, next) }
        }
    }

    private fun refusal(file: AllowFile<*>, path: Path): GitGuardRefusedException? = when {
        file.unreadable -> GitGuardRefusedException("GARD", GuardProblem.UNREADABLE_FILE, path.toString())
        file.readOnly -> GitGuardRefusedException("GARD", GuardProblem.NEWER_FORMAT, path.toString())
        else -> null
    }

    private fun write(block: () -> Unit): Result<Unit> = try {
        block()
        Result.Success(Unit)
    } catch (e: IOException) {
        Result.Failure(com.srctool.graft.core.git.GitExitException("GARD", listOf("guard"), null, e.message.orEmpty()))
    }

    /** This worktree's git dir and the top of its working tree. */
    private suspend fun place(repository: Path): Pair<Path, Path>? {
        val location = recorder.location(repository) ?: return null
        val top = (runner.run(TopLevelCommand(), repository) as? Result.Success)?.data ?: return null
        return location.gitDir to top
    }

    private fun notRepository() = GitNotFoundException("GARD", listOf("rev-parse"), 128, "fatal: not a git repository")

    private companion object {
        const val SUBMODULE = "160000"
        const val MEBI = 1024 * 1024L
        val ORDER = compareBy<GuardFinding>({ it.severity }, { it.path }, { it.line ?: 0 }, { it.rule })
    }
}
