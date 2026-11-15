// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.repositories

import com.srctool.graft.core.git.GitNotFoundException
import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.core.git.GitUnsafeOwnerException
import com.srctool.graft.data.git.api.models.CloneProgress
import com.srctool.graft.data.git.api.models.CloneRequest
import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.data.git.api.models.RepositorySummary
import com.srctool.graft.data.git.api.repositories.RepositoryAccessRepository
import com.srctool.graft.data.git.implementation.source.local.CloneCommand
import com.srctool.graft.data.git.implementation.source.local.InitCommand
import com.srctool.graft.data.git.implementation.source.local.LocateCommand
import com.srctool.graft.data.git.implementation.source.local.Located
import com.srctool.graft.data.git.implementation.source.local.StatusSummaryCommand
import com.srctool.graft.data.git.implementation.source.local.TopLevelCommand
import com.srctool.graft.data.git.implementation.source.local.TrustCommand
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal class GitRepositoryAccessRepository(private val runner: GitRunner) : RepositoryAccessRepository {
    override suspend fun locate(folder: Path): Result<RepositoryLocation> {
        val dir = folder.toAbsolutePath().normalize()
        if (!withContext(Dispatchers.IO) { Files.isDirectory(dir) }) return missing(dir)
        val located =
            when (val r = runner.run(LocateCommand(), dir)) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        val workTree =
            when {
                located.bare -> null

                located.insideGitDir -> workTreeFromGitDir(located)

                else -> when (val r = runner.run(TopLevelCommand(), dir)) {
                    is Result.Success -> r.data
                    is Result.Failure -> return r
                }
            }
        return withContext(Dispatchers.IO) {
            Result.Success(RepositoryLocation(workTree?.real(), located.gitDir.real(), located.commonDir.real()))
        }
    }

    override suspend fun summary(workTree: Path): Result<RepositorySummary> =
        runner.run(StatusSummaryCommand(), workTree)

    override suspend fun init(folder: Path, defaultBranch: String): Result<RepositoryLocation> {
        val dir = folder.toAbsolutePath().normalize()
        withContext(Dispatchers.IO) { Files.createDirectories(dir) }
        return when (val r = runner.run(InitCommand(dir, defaultBranch), dir)) {
            is Result.Success -> locate(dir)
            is Result.Failure -> r
        }
    }

    override fun clone(request: CloneRequest): Flow<CloneProgress> = flow {
        val target = request.directory.toAbsolutePath().normalize()
        val parent = target.parent ?: target
        withContext(Dispatchers.IO) { Files.createDirectories(parent) }
        // Git repeats a phase's line for every percent; the UI only needs the changes.
        emitAll(runner.stream(CloneCommand(request.copy(directory = target)), parent).distinctUntilChanged())
        emit(CloneProgress.Finished(locate(target).getOrThrow()))
    }

    override fun untrustedFolder(error: Throwable): String? =
        (error as? GitUnsafeOwnerException)?.repositoryPath?.takeIf { it.isNotEmpty() }

    override suspend fun trust(folder: String): Result<Unit> {
        val cwd = Paths.get(folder).toAbsolutePath().normalize()
        if (!withContext(Dispatchers.IO) { Files.isDirectory(cwd) }) return missing(cwd)
        return runner.run(TrustCommand(folder), cwd)
    }

    /**
     * Inside `.git` there's no working tree to ask about: it's the folder `.git` sits in, or for a linked worktree,
     * the folder its `gitdir` file points into.
     */
    private suspend fun workTreeFromGitDir(located: Located): Path? = withContext(Dispatchers.IO) {
        val gitDir = located.gitDir
        when {
            gitDir == located.commonDir && gitDir.fileName?.toString() == ".git" -> gitDir.parent

            gitDir != located.commonDir -> {
                val pointer = gitDir.resolve("gitdir")
                if (Files.isRegularFile(pointer)) Paths.get(Files.readString(pointer).trim()).parent else null
            }

            else -> null
        }
    }

    /** What git itself reports for a folder that doesn't exist, without starting git in it. */
    private fun missing(dir: Path): Result.Failure = Result.Failure(
        GitNotFoundException(
            "OPEN",
            listOf("git", "-C", dir.toString(), "rev-parse"),
            128,
            "fatal: cannot change to '$dir': No such file or directory",
        ),
    )

    private fun Path.real(): Path = runCatching { toRealPath() }.getOrElse { toAbsolutePath().normalize() }
}
