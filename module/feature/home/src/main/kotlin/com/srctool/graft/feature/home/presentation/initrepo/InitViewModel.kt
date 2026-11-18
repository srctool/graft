// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.presentation.initrepo

import com.srctool.graft.data.git.api.repositories.IdentityRepository
import com.srctool.graft.feature.home.domain.api.models.InitFile
import com.srctool.graft.feature.home.domain.api.models.InitOptions
import com.srctool.graft.feature.home.domain.api.models.InitOutcome
import com.srctool.graft.feature.home.domain.api.models.InitStep
import com.srctool.graft.feature.home.domain.api.models.LicenseInfo
import com.srctool.graft.feature.home.domain.api.usecases.InitRepositoryUseCase
import com.srctool.graft.feature.home.domain.api.usecases.InitTemplates
import com.srctool.graft.feature.home.presentation.clone.FolderStatus
import com.srctool.sinew.models.Result
import com.srctool.sinew.viewmodel.EventActionViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Init (S3). */
internal data class InitState(
    val folder: String = "",
    val folderStatus: FolderStatus = FolderStatus.Unknown,
    /** The folder is already a repository (it has a `.git`): the dialog offers to open it instead. */
    val isRepository: Boolean = false,
    /** Chosen files that already exist in the folder: Init never overwrites. */
    val taken: List<InitFile> = emptyList(),
    val branch: String = "main",
    val gitignore: String? = null,
    val license: String? = null,
    val holder: String = "",
    val readme: Boolean = false,
    val firstCommit: Boolean = false,
    /** What `git config --global` says now; the first commit needs both. */
    val identityName: String? = null,
    val identityEmail: String? = null,
    val running: Boolean = false,
    val error: Throwable? = null,
    /** `git init` worked and a later step failed: the repository stays. */
    val partial: InitOutcome.Partial? = null,
    val outputShown: Boolean = false,
    val gitignores: List<String> = emptyList(),
    val licenses: List<LicenseInfo> = emptyList(),
) {
    val files: List<InitFile> get() = listOfNotNull(
        InitFile.GITIGNORE.takeIf {
            gitignore != null
        },
        InitFile.LICENSE.takeIf {
            license !=
                null
        },
        InitFile.README.takeIf { readme },
    )

    val licenseNeedsHolder: Boolean get() = licenses.firstOrNull { it.id == license }?.needsHolder == true

    val hasIdentity: Boolean get() = !identityName.isNullOrBlank() && !identityEmail.isNullOrBlank()

    /** The first commit needs something to commit, and an identity to commit as. */
    val firstCommitIssue: FirstCommitIssue?
        get() = when {
            files.isEmpty() -> FirstCommitIssue.NothingToCommit
            !hasIdentity -> FirstCommitIssue.NoIdentity
            else -> null
        }

    val issue: InitIssue?
        get() = when {
            folder.isBlank() -> InitIssue.NoFolder
            folderStatus == FolderStatus.NotAFolder -> InitIssue.FolderIsAFile
            isRepository -> InitIssue.AlreadyRepository
            taken.isNotEmpty() -> InitIssue.FileExists
            branch.isBlank() || branch.any { it.isWhitespace() } || branch.startsWith("-") -> InitIssue.BadBranch
            licenseNeedsHolder && holder.isBlank() -> InitIssue.NoHolder
            else -> null
        }

    val canCreate: Boolean get() = issue == null && !running && partial == null
}

internal enum class InitIssue { NoFolder, FolderIsAFile, AlreadyRepository, FileExists, BadBranch, NoHolder }

internal enum class FirstCommitIssue { NothingToCommit, NoIdentity }

internal sealed interface InitEvent {
    data object Opened : InitEvent

    data class FolderChanged(val folder: String) : InitEvent

    data class FolderPicked(val folder: Path) : InitEvent

    data class BranchChanged(val branch: String) : InitEvent

    data class GitignoreChosen(val name: String?) : InitEvent

    data class LicenseChosen(val id: String?) : InitEvent

    data class HolderChanged(val holder: String) : InitEvent

    data object ReadmeToggled : InitEvent

    data object FirstCommitToggled : InitEvent

    data object Submit : InitEvent

    data object OpenExisting : InitEvent

    data object OpenPartial : InitEvent

    data class OutputShown(val shown: Boolean) : InitEvent
}

internal sealed interface InitAction {
    data object Start : InitAction

    class Edit(val change: (InitState) -> InitState) : InitAction

    data object Create : InitAction

    data object OpenExisting : InitAction

    data object OpenPartial : InitAction
}

internal sealed interface InitEffect {
    /** The repository at [root] exists: Home lists and opens it. */
    data class Created(val root: Path) : InitEffect

    /** The folder is already a repository: Home opens it as it opens any folder. */
    data class OpenExisting(val folder: Path) : InitEffect
}

/**
 * Init (S3): validates the form against the folder and the identity git has, then runs [init]. [folder] is a folder
 * Home opened that wasn't a repository; [home] is where a new folder's path starts. [year] is the license's year.
 */
internal class InitViewModel(
    private val folder: Path?,
    private val home: Path = Paths.get(System.getProperty("user.home")),
    private val init: InitRepositoryUseCase,
    private val identity: IdentityRepository,
    templates: InitTemplates,
    private val year: Int = java.time.Year.now().value,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : EventActionViewModel<InitState, InitEvent, InitAction, InitEffect>(
    InitState(folder = (folder ?: home).toString(), gitignores = templates.gitignores, licenses = templates.licenses),
) {
    private var opened = false

    override fun mapEventToAction(event: InitEvent): InitAction? = when (event) {
        InitEvent.Opened -> if (opened) null else InitAction.Start.also { opened = true }

        is InitEvent.FolderChanged -> InitAction.Edit { it.copy(folder = event.folder, partial = null, error = null) }

        is InitEvent.FolderPicked -> InitAction.Edit {
            it.copy(folder = event.folder.toString(), partial = null, error = null)
        }

        is InitEvent.BranchChanged -> InitAction.Edit { it.copy(branch = event.branch) }

        is InitEvent.GitignoreChosen -> InitAction.Edit { it.copy(gitignore = event.name) }

        is InitEvent.LicenseChosen -> InitAction.Edit { it.copy(license = event.id) }

        is InitEvent.HolderChanged -> InitAction.Edit { it.copy(holder = event.holder) }

        InitEvent.ReadmeToggled -> InitAction.Edit { it.copy(readme = !it.readme) }

        InitEvent.FirstCommitToggled -> InitAction.Edit { it.copy(firstCommit = !it.firstCommit) }

        InitEvent.Submit -> InitAction.Create

        InitEvent.OpenExisting -> InitAction.OpenExisting

        InitEvent.OpenPartial -> InitAction.OpenPartial

        is InitEvent.OutputShown -> InitAction.Edit { it.copy(outputShown = event.shown) }
    }

    override suspend fun onAction(action: InitAction) {
        when (action) {
            InitAction.Start -> {
                // The first commit and the license's holder start from the identity git already has.
                (identity.read() as? Result.Success)?.data?.let { id ->
                    setState {
                        copy(
                            identityName = id.name?.value,
                            identityEmail = id.email?.value,
                            holder = holder.ifBlank {
                                id.name?.value.orEmpty()
                            },
                        )
                    }
                }
                inspect()
            }

            is InitAction.Edit -> {
                if (currentState.running) return
                setState(action.change)
                // Without a way to commit, the box is off.
                if (currentState.firstCommit &&
                    currentState.firstCommitIssue != null
                ) {
                    setState { copy(firstCommit = false) }
                }
                inspect()
            }

            InitAction.Create -> if (currentState.canCreate) create()

            InitAction.OpenExisting -> emitEffect(InitEffect.OpenExisting(Paths.get(currentState.folder.trim())))

            InitAction.OpenPartial ->
                currentState.partial?.let { p ->
                    emitEffect(InitEffect.Created(p.location.workTree ?: p.location.gitDir))
                }
        }
    }

    /** What's in the folder now: whether it's a repository, and which of the chosen files are already there. */
    private suspend fun inspect() {
        val s = currentState
        val text = s.folder.trim()
        val (status, repository, taken) =
            withContext(io) {
                val path = runCatching { Paths.get(text) }.getOrNull()
                when {
                    text.isEmpty() || path == null || Files.notExists(
                        path,
                    ) -> Triple(FolderStatus.Free, false, emptyList())

                    !Files.isDirectory(path) -> Triple(FolderStatus.NotAFolder, false, emptyList())

                    else -> Triple(
                        FolderStatus.EmptyFolder,
                        Files.exists(path.resolve(".git")),
                        s.files.filter {
                            Files.exists(path.resolve(it.fileName))
                        },
                    )
                }
            }
        setState { copy(folderStatus = status, isRepository = repository, taken = taken) }
    }

    private suspend fun create() {
        val s = currentState
        setState { copy(running = true, error = null, partial = null) }
        val options =
            InitOptions(
                folder = Paths.get(s.folder.trim()),
                defaultBranch = s.branch.trim(),
                gitignore = s.gitignore,
                license = s.license,
                holder = s.holder.trim(),
                readme = s.readme,
                firstCommit = s.firstCommit && s.firstCommitIssue == null,
                year = year,
            )
        when (val outcome = init.create(options)) {
            is InitOutcome.Created -> {
                setState { copy(running = false) }
                emitEffect(InitEffect.Created(outcome.location.workTree ?: outcome.location.gitDir))
            }

            is InitOutcome.Partial -> setState { copy(running = false, partial = outcome) }

            is InitOutcome.Failed -> setState { copy(running = false, error = outcome.error) }
        }
    }

    /** What a step is called in the dialog's message: the file, or the commit. */
    internal fun stepName(step: InitStep): String = when (step) {
        is InitStep.Writing -> step.file.fileName
        InitStep.Committing -> "the first commit"
    }
}
