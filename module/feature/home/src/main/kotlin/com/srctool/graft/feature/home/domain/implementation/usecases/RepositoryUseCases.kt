// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.domain.implementation.usecases

import com.srctool.graft.core.exception.GraftCodes
import com.srctool.graft.data.app.api.models.Workspace
import com.srctool.graft.data.app.api.repositories.FolderScanRepository
import com.srctool.graft.data.app.api.repositories.KnownRepositoryRepository
import com.srctool.graft.data.app.api.repositories.WorkspaceRepository
import com.srctool.graft.data.git.api.models.CloneProgress
import com.srctool.graft.data.git.api.models.CloneRequest
import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.data.git.api.models.RepositorySummary
import com.srctool.graft.data.git.api.repositories.RepositoryAccessRepository
import com.srctool.graft.feature.home.domain.api.models.InitFile
import com.srctool.graft.feature.home.domain.api.models.InitOptions
import com.srctool.graft.feature.home.domain.api.models.InitOutcome
import com.srctool.graft.feature.home.domain.api.models.InitStep
import com.srctool.graft.feature.home.domain.api.models.OpenOutcome
import com.srctool.graft.feature.home.domain.api.models.ScannedRepository
import com.srctool.graft.feature.home.domain.api.usecases.CloneRepositoryUseCase
import com.srctool.graft.feature.home.domain.api.usecases.InitRepositoryUseCase
import com.srctool.graft.feature.home.domain.api.usecases.InitTemplates
import com.srctool.graft.feature.home.domain.api.usecases.OpenRepositoryUseCase
import com.srctool.graft.feature.home.domain.api.usecases.RepositorySummaryUseCase
import com.srctool.graft.feature.home.domain.api.usecases.ScanFolderUseCase
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.SinewException
import com.srctool.sinew.models.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** The repository's root as a tab opens it: the working tree, or the git folder of a bare repository. */
private val RepositoryLocation.root: Path get() = workTree ?: gitDir

internal class GitOpenRepositoryUseCase(
    private val access: RepositoryAccessRepository,
    private val known: KnownRepositoryRepository,
) : OpenRepositoryUseCase {
    override suspend fun open(folder: Path): OpenOutcome {
        // Git says "not a repository" for a missing folder too; the two need different answers.
        if (!withContext(Dispatchers.IO) { Files.isDirectory(folder) }) return OpenOutcome.FolderMissing(folder)
        val location =
            when (val r = access.locate(folder)) {
                is Result.Success -> r.data
                is Result.Failure -> return failed(folder, r.error)
            }
        // The repository is open either way; a recent list that can't be written shows its own error on Home.
        known.opened(location.root)
        return OpenOutcome.Opened(location, location.root, withContext(Dispatchers.IO) { subfolder(folder, location) })
    }

    override suspend fun trustAndOpen(untrusted: OpenOutcome.Untrusted, folder: Path): OpenOutcome =
        when (val r = access.trust(untrusted.folder)) {
            is Result.Success -> open(folder)
            is Result.Failure -> OpenOutcome.Failed(r.error)
        }

    private fun failed(folder: Path, error: Throwable): OpenOutcome {
        access.untrustedFolder(error)?.let { path ->
            return OpenOutcome.Untrusted(path, listOf("git", "config", "--global", "--add", "safe.directory", path))
        }
        return when ((error as? SinewException)?.typeCode) {
            GraftCodes.Types.NOT_FOUND -> OpenOutcome.NotARepository(folder)
            else -> OpenOutcome.Failed(error)
        }
    }

    /** Where [folder] sits inside the working tree; null at the root, inside `.git`, or for a bare repository. */
    private fun subfolder(folder: Path, location: RepositoryLocation): Path? {
        val workTree = location.workTree ?: return null
        val real =
            try {
                folder.toRealPath()
            } catch (_: IOException) {
                return null
            }
        if (!real.startsWith(workTree) || real == workTree || real.startsWith(location.gitDir)) return null
        return workTree.relativize(real)
    }
}

internal class GitScanFolderUseCase(
    private val scanner: FolderScanRepository,
    private val known: KnownRepositoryRepository,
    private val workspaces: WorkspaceRepository,
) : ScanFolderUseCase {
    override suspend fun scan(folder: Path): Result<List<ScannedRepository>> {
        val found =
            when (val r = scanner.scan(folder)) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        // Marking what's known is a convenience: an unreadable recent list just marks nothing.
        val listed = (known.list() as? Result.Success)?.data?.value.orEmpty().map { it.path }.toSet()
        return Result.Success(found.map { ScannedRepository(it, it in listed) })
    }

    override suspend fun addAsWorkspace(name: String, repositories: List<Path>): Result<Workspace> =
        workspaces.create(name, repositories)
}

internal class GitCloneRepositoryUseCase(
    private val access: RepositoryAccessRepository,
    private val known: KnownRepositoryRepository,
) : CloneRepositoryUseCase {
    override fun clone(request: CloneRequest): Flow<CloneProgress> = access.clone(request).onEach { step ->
        if (step is CloneProgress.Finished) known.opened(step.location.root)
    }
}

internal class GitInitRepositoryUseCase(
    private val access: RepositoryAccessRepository,
    private val known: KnownRepositoryRepository,
    private val templates: InitTemplates = BundledInitTemplates,
) : InitRepositoryUseCase {
    override suspend fun init(folder: Path, defaultBranch: String): Result<RepositoryLocation> =
        access.init(folder, defaultBranch).map { location ->
            known.opened(location.root)
            location
        }

    override suspend fun create(options: InitOptions): InitOutcome {
        val location =
            when (val r = init(options.folder, options.defaultBranch)) {
                is Result.Success -> r.data
                is Result.Failure -> return InitOutcome.Failed(r.error)
            }
        val root = location.workTree ?: return InitOutcome.Created(location)
        val written = ArrayList<String>()
        for (file in options.files) {
            val text =
                when (file) {
                    InitFile.GITIGNORE -> templates.gitignore(options.gitignore.orEmpty())
                    InitFile.LICENSE -> templates.license(options.license.orEmpty(), options.year, options.holder)
                    InitFile.README -> "# ${root.fileName}\n"
                }
                    ?: return InitOutcome.Partial(
                        location,
                        InitStep.Writing(file),
                        IllegalStateException("${file.fileName} has no template"),
                    )
            try {
                // CREATE_NEW: a file that's already there is never overwritten.
                withContext(Dispatchers.IO) {
                    Files.writeString(root.resolve(file.fileName), text, Charsets.UTF_8, StandardOpenOption.CREATE_NEW)
                }
                written += file.fileName
            } catch (e: IOException) {
                return InitOutcome.Partial(location, InitStep.Writing(file), e)
            }
        }
        if (options.firstCommit && written.isNotEmpty()) {
            val committed = access.initialCommit(root, written, "Initial commit")
            if (committed is Result.Failure) return InitOutcome.Partial(location, InitStep.Committing, committed.error)
        }
        return InitOutcome.Created(location)
    }
}

internal class GitRepositorySummaryUseCase(private val access: RepositoryAccessRepository, limit: Int = 4) :
    RepositorySummaryUseCase {
    private val permits = Semaphore(limit)

    override suspend fun summarize(workTree: Path): Result<RepositorySummary> =
        permits.withPermit { access.summary(workTree) }
}
