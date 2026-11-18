// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.presentation

import com.srctool.graft.core.exception.CommandOutput
import com.srctool.graft.data.app.api.models.AppSettings
import com.srctool.graft.data.app.api.models.KnownRepository
import com.srctool.graft.data.app.api.models.Saved
import com.srctool.graft.data.app.api.models.SavedStatus
import com.srctool.graft.data.app.api.models.Workspace
import com.srctool.graft.data.app.api.repositories.KnownRepositoryRepository
import com.srctool.graft.data.app.api.repositories.SettingsRepository
import com.srctool.graft.data.app.api.repositories.WorkspaceRepository
import com.srctool.graft.data.git.api.models.CloneProgress
import com.srctool.graft.data.git.api.models.CloneRequest
import com.srctool.graft.data.git.api.models.ConfigValue
import com.srctool.graft.data.git.api.models.GitInstall
import com.srctool.graft.data.git.api.models.GitInstallStatus
import com.srctool.graft.data.git.api.models.GlobalIdentity
import com.srctool.graft.data.git.api.models.Head
import com.srctool.graft.data.git.api.models.IdentityWrite
import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.data.git.api.models.RepositorySummary
import com.srctool.graft.data.git.api.models.Upstream
import com.srctool.graft.data.git.api.repositories.GitInstallRepository
import com.srctool.graft.data.git.api.repositories.IdentityRepository
import com.srctool.graft.feature.home.domain.api.models.InitOptions
import com.srctool.graft.feature.home.domain.api.models.InitOutcome
import com.srctool.graft.feature.home.domain.api.models.OpenOutcome
import com.srctool.graft.feature.home.domain.api.models.ScannedRepository
import com.srctool.graft.feature.home.domain.api.usecases.CloneRepositoryUseCase
import com.srctool.graft.feature.home.domain.api.usecases.InitRepositoryUseCase
import com.srctool.graft.feature.home.domain.api.usecases.OpenRepositoryUseCase
import com.srctool.graft.feature.home.domain.api.usecases.RepositorySummaryUseCase
import com.srctool.graft.feature.home.domain.api.usecases.ScanFolderUseCase
import com.srctool.sinew.models.ErrorMessage
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.SinewException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.file.Path
import java.nio.file.Paths

/** A failure with a code and git's output, as the data layer reports one. */
internal class FakeFailure(text: String = "boom") :
    SinewException("GIT", ExceptionLayer.Repository, "TEST"),
    CommandOutput {
    override val typeCode: String = "EXIT"
    override val errorMessage: ErrorMessage = ErrorMessage.Local("test.failed", emptyMap(), "Git couldn't do that.")
    override val argv: List<String> = listOf("git", "test")
    override val exitCode: Int? = 128
    override val stderr: String = "fatal: $text"
}

internal fun path(name: String): Path = Paths.get("/work").resolve(name)

internal class FakeKnown(
    repositories: List<KnownRepository> = emptyList(),
    var status: SavedStatus = SavedStatus.Current,
) : KnownRepositoryRepository {
    val repositories = repositories.toMutableList()
    var writeFails = false

    override suspend fun list(): Result<Saved<List<KnownRepository>>> =
        Result.Success(Saved(repositories.toList(), status))

    private fun write(change: () -> Unit): Result<Unit> {
        if (writeFails) return Result.Failure(FakeFailure("newer"))
        change()
        return Result.Success(Unit)
    }

    override suspend fun opened(path: Path): Result<Unit> = write {
        val i = repositories.indexOfFirst { it.path == path }
        val entry = KnownRepository(path, 1_000, repositories.getOrNull(i)?.pinned ?: false, false)
        if (i >= 0) repositories[i] = entry else repositories += entry
    }

    override suspend fun setPinned(path: Path, pinned: Boolean): Result<Unit> = write {
        val i = repositories.indexOfFirst { it.path == path }
        repositories[i] = repositories[i].copy(pinned = pinned)
    }

    override suspend fun remove(path: Path): Result<Unit> = write { repositories.removeAll { it.path == path } }

    override suspend fun relocate(from: Path, to: Path): Result<Unit> = write {
        val i = repositories.indexOfFirst { it.path == from }
        repositories[i] = repositories[i].copy(path = to, missing = false)
    }
}

internal class FakeWorkspaces(
    workspaces: List<Workspace> = emptyList(),
    var status: SavedStatus = SavedStatus.Current,
) : WorkspaceRepository {
    val workspaces = workspaces.toMutableList()
    private var next = 1

    override suspend fun list(): Result<Saved<List<Workspace>>> = Result.Success(Saved(workspaces.toList(), status))

    override suspend fun create(name: String, repositories: List<Path>): Result<Workspace> {
        val w = Workspace("w${next++}", name, repositories)
        workspaces += w
        return Result.Success(w)
    }

    private fun update(id: String, change: (Workspace) -> Workspace): Result<Workspace> {
        val i = workspaces.indexOfFirst { it.id == id }
        workspaces[i] = change(workspaces[i])
        return Result.Success(workspaces[i])
    }

    override suspend fun rename(id: String, name: String) = update(id) { it.copy(name = name) }

    override suspend fun add(id: String, repositories: List<Path>) = update(id) {
        it.copy(
            repositories = (
                it.repositories +
                    repositories
                ).distinct(),
        )
    }

    override suspend fun removeRepository(id: String, repository: Path) = update(id) {
        it.copy(
            // Not `-`: a Path is Iterable<Path>, so that would remove its name parts.
            repositories = it.repositories.filterNot { r -> r == repository },
        )
    }

    override suspend fun delete(id: String): Result<Unit> {
        workspaces.removeAll { it.id == id }
        return Result.Success(Unit)
    }
}

internal class FakeOpen(var outcomes: Map<Path, OpenOutcome> = emptyMap(), private val known: FakeKnown? = null) :
    OpenRepositoryUseCase {
    val opened = ArrayList<Path>()
    val trusted = ArrayList<OpenOutcome.Untrusted>()

    override suspend fun open(folder: Path): OpenOutcome {
        opened.add(folder)
        val outcome =
            outcomes[folder]
                ?: OpenOutcome.Opened(
                    RepositoryLocation(folder, folder.resolve(".git"), folder.resolve(".git")),
                    folder,
                    null,
                )
        if (outcome is OpenOutcome.Opened) known?.opened(outcome.root)
        return outcome
    }

    override suspend fun trustAndOpen(untrusted: OpenOutcome.Untrusted, folder: Path): OpenOutcome {
        trusted += untrusted
        outcomes = outcomes.filterKeys { it != folder }
        return open(folder)
    }
}

internal class FakeScan(
    var found: List<ScannedRepository> = emptyList(),
    private val workspaces: FakeWorkspaces? = null,
) : ScanFolderUseCase {
    override suspend fun scan(folder: Path): Result<List<ScannedRepository>> = Result.Success(found)

    override suspend fun addAsWorkspace(name: String, repositories: List<Path>): Result<Workspace> =
        workspaces!!.create(name, repositories)
}

internal class FakeSummaries(var summaries: Map<Path, RepositorySummary> = emptyMap()) : RepositorySummaryUseCase {
    val asked = ArrayList<Path>()

    override suspend fun summarize(workTree: Path): Result<RepositorySummary> {
        asked.add(workTree)
        return summaries[workTree]?.let { Result.Success(it) } ?: Result.Failure(FakeFailure("no status"))
    }
}

internal fun summary(branch: String, dirty: Boolean = false, ahead: Int = 0, behind: Int = 0) =
    RepositorySummary(Head.Branch(branch, null), Upstream("origin/$branch", ahead, behind), dirty, if (dirty) 2 else 0)

internal class FakeClone(
    var steps: List<CloneProgress> = emptyList(),
    var failure: Throwable? = null,
    var hang: Boolean = false,
) : CloneRepositoryUseCase {
    val requests = ArrayList<CloneRequest>()
    var cancelled = false

    override fun clone(request: CloneRequest): Flow<CloneProgress> = flow {
        requests += request
        failure?.let { throw it }
        steps.forEach { emit(it) }
        if (hang) {
            try {
                kotlinx.coroutines.awaitCancellation()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
        }
    }
}

internal class FakeInit(
    var outcome: (InitOptions) -> InitOutcome = { o ->
        InitOutcome.Created(RepositoryLocation(o.folder, o.folder.resolve(".git"), o.folder.resolve(".git")))
    },
) : InitRepositoryUseCase {
    val options = ArrayList<InitOptions>()

    override suspend fun init(folder: Path, defaultBranch: String): Result<RepositoryLocation> = error("not used")

    override suspend fun create(options: InitOptions): InitOutcome {
        this.options += options
        return outcome(options)
    }
}

internal class FakeIdentity(var name: String? = "Dwiki Riyadi", var email: String? = "dwiki@example.invalid") :
    IdentityRepository {
    override suspend fun read(): Result<GlobalIdentity> = Result.Success(
        GlobalIdentity(
            name?.let {
                ConfigValue(it, "file:/home/.gitconfig")
            },
            email?.let { ConfigValue(it, "file:/home/.gitconfig") },
        ),
    )

    var failure: SinewException? = null
    val writes = ArrayList<Pair<String, String>>()

    override suspend fun write(name: String, email: String): Result<IdentityWrite> {
        failure?.let { return Result.Failure(it) }
        writes += name to email
        this.name = name
        this.email = email
        val read = (read() as Result.Success).data
        return Result.Success(IdentityWrite(listOf("user.name", "user.email"), read))
    }
}

internal class FakeSettings(
    var value: AppSettings = AppSettings(onboarded = true),
    var failure: SinewException? = null,
) : SettingsRepository {
    override suspend fun read(): Result<Saved<AppSettings>> = Result.Success(Saved(value, SavedStatus.Current))

    override suspend fun update(change: (AppSettings) -> AppSettings): Result<AppSettings> {
        failure?.let { return Result.Failure(it) }
        value = change(value)
        return Result.Success(value)
    }
}

internal class FakeInstall(var status: GitInstallStatus = GitInstallStatus.Ready(GitInstall("2.54.0", null))) :
    GitInstallRepository {
    var checks = 0

    override suspend fun check(): GitInstallStatus {
        checks++
        return status
    }
}
