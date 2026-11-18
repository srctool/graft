// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.presentation.clone

import androidx.lifecycle.viewModelScope
import com.srctool.graft.data.git.api.models.CloneProgress
import com.srctool.graft.data.git.api.models.CloneRequest
import com.srctool.graft.feature.home.domain.api.usecases.CloneRepositoryUseCase
import com.srctool.sinew.viewmodel.EventActionViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** What the folder field's text points at now. */
internal enum class FolderStatus { Unknown, Free, EmptyFolder, NotEmpty, NotAFolder }

/** Clone (S2). The folder is a full path; until it's edited by hand it follows the URL's repository name. */
internal data class CloneState(
    val url: String = "",
    val folder: String = "",
    val folderEdited: Boolean = false,
    val folderStatus: FolderStatus = FolderStatus.Unknown,
    val optionsOpen: Boolean = false,
    val shallow: Boolean = false,
    val depth: String = "50",
    val singleBranch: Boolean = false,
    val branch: String = "",
    val submodules: Boolean = false,
    val partial: Boolean = false,
    val running: Boolean = false,
    /** Git's phase while it runs, such as "Receiving objects", with its percent when git reports one. */
    val phase: String? = null,
    val percent: Int? = null,
    val done: Long? = null,
    val total: Long? = null,
    val error: Throwable? = null,
    val outputShown: Boolean = false,
) {
    val depthValue: Int? get() = depth.trim().toIntOrNull()?.takeIf { it > 0 }

    /** Why **Clone** is off, or null when it's on. A blank URL has no message: nothing's been typed yet. */
    val issue: CloneIssue?
        get() = when {
            url.isBlank() -> CloneIssue.NoUrl
            url.trim().startsWith("-") -> CloneIssue.UrlLooksLikeOption
            folder.isBlank() -> CloneIssue.NoFolder
            folderStatus == FolderStatus.NotEmpty -> CloneIssue.FolderNotEmpty
            folderStatus == FolderStatus.NotAFolder -> CloneIssue.FolderIsAFile
            shallow && depthValue == null -> CloneIssue.BadDepth
            else -> null
        }

    val canClone: Boolean get() = issue == null && !running
}

internal enum class CloneIssue { NoUrl, UrlLooksLikeOption, NoFolder, FolderNotEmpty, FolderIsAFile, BadDepth }

internal sealed interface CloneEvent {
    data object Opened : CloneEvent

    data class UrlChanged(val url: String) : CloneEvent

    data class FolderChanged(val folder: String) : CloneEvent

    /** The picker chose a parent folder: the repository's folder goes inside it. */
    data class ParentPicked(val parent: Path) : CloneEvent

    data object OptionsToggled : CloneEvent

    data object ShallowToggled : CloneEvent

    data class DepthChanged(val depth: String) : CloneEvent

    data object SingleBranchToggled : CloneEvent

    data class BranchChanged(val branch: String) : CloneEvent

    data object SubmodulesToggled : CloneEvent

    data object PartialToggled : CloneEvent

    data object Submit : CloneEvent

    data object Cancel : CloneEvent

    data class OutputShown(val shown: Boolean) : CloneEvent
}

internal sealed interface CloneAction {
    data object Start : CloneAction

    class Edit(val change: (CloneState) -> CloneState) : CloneAction

    data object Clone : CloneAction

    data object Cancel : CloneAction
}

internal sealed interface CloneEffect {
    /** The clone finished: the repository at [root] is recorded and ready to open. */
    data class Cloned(val root: Path) : CloneEffect
}

/**
 * Clone (S2): validates the form, runs the clone as a flow and shows git's phases, and cancels it by cancelling that
 * flow, which stops git and everything it started and removes the folder the clone made (the data layer does both).
 * [folder] is a folder to clone into (one Home opened that wasn't a repository); [home] is where new folders start.
 */
internal class CloneViewModel(
    private val folder: Path?,
    private val home: Path = Paths.get(System.getProperty("user.home")),
    private val clone: CloneRepositoryUseCase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : EventActionViewModel<CloneState, CloneEvent, CloneAction, CloneEffect>(CloneState()) {
    private var opened = false
    private var job: Job? = null

    override fun mapEventToAction(event: CloneEvent): CloneAction? = when (event) {
        CloneEvent.Opened -> if (opened) null else CloneAction.Start.also { opened = true }

        is CloneEvent.UrlChanged ->
            CloneAction.Edit { s ->
                val next = s.copy(url = event.url)
                if (s.folderEdited) next else next.copy(folder = suggestedFolder(home, event.url))
            }

        is CloneEvent.FolderChanged -> CloneAction.Edit { it.copy(folder = event.folder, folderEdited = true) }

        is CloneEvent.ParentPicked ->
            CloneAction.Edit { s ->
                s.copy(folder = event.parent.resolve(folderNameFor(s.url)).toString(), folderEdited = true)
            }

        CloneEvent.OptionsToggled -> CloneAction.Edit { it.copy(optionsOpen = !it.optionsOpen) }

        CloneEvent.ShallowToggled -> CloneAction.Edit { it.copy(shallow = !it.shallow) }

        is CloneEvent.DepthChanged -> CloneAction.Edit { it.copy(depth = event.depth) }

        CloneEvent.SingleBranchToggled -> CloneAction.Edit { it.copy(singleBranch = !it.singleBranch) }

        is CloneEvent.BranchChanged -> CloneAction.Edit { it.copy(branch = event.branch) }

        CloneEvent.SubmodulesToggled -> CloneAction.Edit { it.copy(submodules = !it.submodules) }

        CloneEvent.PartialToggled -> CloneAction.Edit { it.copy(partial = !it.partial) }

        CloneEvent.Submit -> CloneAction.Clone

        CloneEvent.Cancel -> CloneAction.Cancel

        is CloneEvent.OutputShown -> CloneAction.Edit { it.copy(outputShown = event.shown) }
    }

    override suspend fun onAction(action: CloneAction) {
        when (action) {
            CloneAction.Start -> {
                // A folder Home opened is cloned into as it is; otherwise the folder follows the URL under home.
                val preset = folder?.toString()
                if (preset != null) setState { copy(folder = preset, folderEdited = true) }
                checkFolder()
            }

            is CloneAction.Edit -> {
                if (currentState.running) return
                setState(action.change)
                checkFolder()
            }

            // Checked here, not when the event arrived: the edits before it have run by now.
            CloneAction.Clone -> if (currentState.canClone) start()

            CloneAction.Cancel -> {
                job?.cancel()
                setState { copy(running = false, phase = null, percent = null, done = null, total = null) }
            }
        }
    }

    private suspend fun checkFolder() {
        val text = currentState.folder.trim()
        val status =
            if (text.isEmpty()) {
                FolderStatus.Unknown
            } else {
                withContext(io) {
                    val path = runCatching { Paths.get(text) }.getOrNull()
                    when {
                        path == null || Files.notExists(path) -> FolderStatus.Free
                        !Files.isDirectory(path) -> FolderStatus.NotAFolder
                        Files.list(path).use { it.findAny().isPresent } -> FolderStatus.NotEmpty
                        else -> FolderStatus.EmptyFolder
                    }
                }
            }
        setState { copy(folderStatus = status) }
    }

    private fun start() {
        val s = currentState
        val request =
            CloneRequest(
                url = s.url.trim(),
                directory = Paths.get(s.folder.trim()),
                depth = s.depthValue.takeIf { s.shallow },
                singleBranch = s.singleBranch,
                branch = s.branch.trim().takeIf { s.singleBranch && it.isNotEmpty() },
                recurseSubmodules = s.submodules,
                partial = s.partial,
            )
        setState { copy(running = true, error = null, phase = null, percent = null) }
        job =
            viewModelScope.launch {
                try {
                    clone.clone(request).collect { step ->
                        when (step) {
                            is CloneProgress.Phase -> setState {
                                copy(phase = step.name, percent = step.percent, done = step.done, total = step.total)
                            }

                            is CloneProgress.Finished -> {
                                setState { copy(running = false) }
                                emitEffect(CloneEffect.Cloned(step.location.workTree ?: step.location.gitDir))
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    setState { copy(running = false, phase = null, error = e) }
                    checkFolder()
                }
            }
    }
}

/** The folder a clone of [url] goes in: [home] and the repository's name. */
internal fun suggestedFolder(home: Path, url: String): String {
    val name = folderNameFor(url)
    return if (name.isEmpty()) "" else home.resolve(name).toString()
}

/**
 * The repository's name in [url], for its folder: `https://github.com/acme/app.git` and `git@github.com:acme/app.git`
 * both give `app`. Characters a folder name can't hold are dropped, and so are leading dots.
 */
internal fun folderNameFor(url: String): String {
    val last =
        url.trim().trimEnd('/', '\\').removeSuffix(".git").trimEnd('/', '\\')
            .substringAfterLast('/').substringAfterLast('\\').substringAfterLast(':')
    return last.filter { it !in "<>:\"|?*" && it.code >= 32 }.trimStart('.').trim()
}
