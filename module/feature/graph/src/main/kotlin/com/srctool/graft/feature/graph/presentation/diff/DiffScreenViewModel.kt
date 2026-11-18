// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.diff

import androidx.lifecycle.viewModelScope
import com.srctool.graft.core.ui.git.DiffDocument
import com.srctool.graft.core.ui.git.DiffHunkView
import com.srctool.graft.core.ui.git.DiffLineKind
import com.srctool.graft.core.ui.git.DiffLineView
import com.srctool.graft.core.ui.git.DiffMode
import com.srctool.graft.data.app.api.models.DiffStyle
import com.srctool.graft.data.app.api.repositories.SettingsRepository
import com.srctool.graft.data.git.api.models.ChangedFile
import com.srctool.graft.data.git.api.models.DiffLine
import com.srctool.graft.data.git.api.models.DiffOptions
import com.srctool.graft.data.git.api.models.DiffTarget
import com.srctool.graft.data.git.api.models.FileContent
import com.srctool.graft.data.git.api.models.Hunk
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.repositories.DiffRepository
import com.srctool.graft.data.git.api.repositories.FileContents
import com.srctool.graft.presentation.view.CenterView
import com.srctool.graft.presentation.view.RepositoryViewState
import com.srctool.sinew.models.Result
import com.srctool.sinew.viewmodel.EventActionViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.file.Path

/** What the diff screen shows for the current file (S6, Task 14 wireframe). */
internal sealed interface DiffBody {
    data object Loading : DiffBody

    data class Text(val document: DiffDocument) : DiffBody

    /** Sizes in bytes, null where the file doesn't exist on that side; [images] when both sides could be drawn. */
    data class Binary(val oldSize: Long?, val newSize: Long?, val images: ImagePair? = null) : DiffBody

    data class TooLarge(val size: Long) : DiffBody

    data class Renamed(val from: String) : DiffBody

    data class ModeOnly(val from: String, val to: String) : DiffBody

    data class Submodule(val from: ObjectId?, val to: ObjectId?) : DiffBody

    data class Failed(val error: Throwable) : DiffBody
}

/** An image's bytes before and after (null where the file didn't exist). */
internal class ImagePair(val old: ByteArray?, val new: ByteArray?)

internal data class DiffScreenState(
    val commit: ObjectId? = null,
    val subject: String = "",
    val files: List<ChangedFile> = emptyList(),
    val index: Int = -1,
    val body: DiffBody = DiffBody.Loading,
    val mode: DiffMode = DiffMode.UNIFIED,
    val ignoreWhitespace: Boolean = false,
    val outputShown: Boolean = false,
) {
    val file: ChangedFile? get() = files.getOrNull(index)
}

internal sealed interface DiffScreenEvent {
    data object Opened : DiffScreenEvent

    data object Back : DiffScreenEvent

    /** Alt+Shift+→ (+1) and Alt+Shift+← (-1). */
    data class StepFile(val by: Int) : DiffScreenEvent

    data class ModeChosen(val mode: DiffMode) : DiffScreenEvent

    data object ModeToggled : DiffScreenEvent

    data object WhitespaceToggled : DiffScreenEvent

    data object ShowAnyway : DiffScreenEvent

    data object Retry : DiffScreenEvent

    data class OutputShown(val shown: Boolean) : DiffScreenEvent
}

internal sealed interface DiffScreenAction {
    data object Start : DiffScreenAction

    data object Back : DiffScreenAction

    data class Step(val by: Int) : DiffScreenAction

    class Mode(val pick: (DiffMode) -> DiffMode) : DiffScreenAction

    data object Whitespace : DiffScreenAction

    data object ShowAnyway : DiffScreenAction

    data object Reload : DiffScreenAction

    data class Output(val shown: Boolean) : DiffScreenAction
}

/**
 * The diff screen (S6): shows the file the tab's [shared] center names, steps through the commit's files, and keeps the
 * Diff style and Ignore whitespace settings in [settings] (Settings Reference: app scope). Reads files through one
 * [FileContents] process, closed with the view model.
 */
internal class DiffScreenViewModel(
    private val repository: Path,
    private val shared: RepositoryViewState,
    private val diffs: DiffRepository,
    private val settings: SettingsRepository,
) : EventActionViewModel<DiffScreenState, DiffScreenEvent, DiffScreenAction, Nothing>(DiffScreenState()) {
    private var opened = false
    private var loadJob: Job? = null
    private var limit: Long = FileContents.DEFAULT_MAX_BYTES
    private var anyway = false
    private var shown: CenterView.Diff? = null
    private val contents: FileContents by lazy { diffs.contents(repository) }
    private var contentsOpen = false

    override fun mapEventToAction(event: DiffScreenEvent): DiffScreenAction? = when (event) {
        DiffScreenEvent.Opened -> if (opened) null else DiffScreenAction.Start.also { opened = true }

        DiffScreenEvent.Back -> DiffScreenAction.Back

        is DiffScreenEvent.StepFile -> DiffScreenAction.Step(event.by)

        is DiffScreenEvent.ModeChosen -> DiffScreenAction.Mode { event.mode }

        DiffScreenEvent.ModeToggled ->
            DiffScreenAction.Mode { if (it == DiffMode.UNIFIED) DiffMode.SIDE_BY_SIDE else DiffMode.UNIFIED }

        DiffScreenEvent.WhitespaceToggled -> DiffScreenAction.Whitespace

        DiffScreenEvent.ShowAnyway -> if (currentState.body is DiffBody.TooLarge) DiffScreenAction.ShowAnyway else null

        DiffScreenEvent.Retry -> DiffScreenAction.Reload

        is DiffScreenEvent.OutputShown -> DiffScreenAction.Output(event.shown)
    }

    override suspend fun onAction(action: DiffScreenAction) {
        when (action) {
            DiffScreenAction.Start -> start()

            DiffScreenAction.Back -> shared.center.value = CenterView.Graph

            is DiffScreenAction.Step -> {
                val s = currentState
                val next = s.files.getOrNull(s.index + action.by) ?: return
                val commit = s.commit ?: return
                shared.center.value = CenterView.Diff(commit, next.path)
            }

            is DiffScreenAction.Mode -> {
                val mode = action.pick(currentState.mode)
                setState { copy(mode = mode) }
                settings.update {
                    it.copy(
                        diffStyle = if (mode ==
                            DiffMode.SIDE_BY_SIDE
                        ) {
                            DiffStyle.SIDE_BY_SIDE
                        } else {
                            DiffStyle.UNIFIED
                        },
                    )
                }
            }

            DiffScreenAction.Whitespace -> {
                val ignore = !currentState.ignoreWhitespace
                setState { copy(ignoreWhitespace = ignore) }
                settings.update { it.copy(ignoreWhitespace = ignore) }
                reload()
            }

            DiffScreenAction.ShowAnyway -> {
                anyway = true
                reload()
            }

            DiffScreenAction.Reload -> {
                // A failed file list is read again too.
                if (currentState.files.isEmpty()) shown = null
                (shared.center.value as? CenterView.Diff)?.let { show(it) }
            }

            is DiffScreenAction.Output -> setState { copy(outputShown = action.shown) }
        }
    }

    private suspend fun start() {
        (settings.read() as? Result.Success)?.data?.value?.let { s ->
            limit = s.inlineDiffLimitBytes
            setState {
                copy(
                    mode = if (s.diffStyle ==
                        DiffStyle.SIDE_BY_SIDE
                    ) {
                        DiffMode.SIDE_BY_SIDE
                    } else {
                        DiffMode.UNIFIED
                    },
                    ignoreWhitespace = s.ignoreWhitespace,
                )
            }
        }
        viewModelScope.launch {
            shared.center.collect { center -> if (center is CenterView.Diff) show(center) }
        }
    }

    private fun reload() {
        shown?.let {
            shown = null
            show(it)
        }
    }

    private fun show(center: CenterView.Diff) {
        if (center == shown) return
        // The commit whose files are loaded, not the last file shown: a reload forgets that, and keeps the files.
        val sameCommit = currentState.commit == center.commit && currentState.files.isNotEmpty()
        shown = center
        if (!sameCommit) anyway = false
        loadJob?.cancel()
        setState { copy(body = DiffBody.Loading, outputShown = false) }
        loadJob =
            viewModelScope.launch {
                contentsOpen = true
                val target = DiffTarget.Commit(center.commit)
                val options = DiffOptions(ignoreWhitespace = currentState.ignoreWhitespace)
                if (!sameCommit) {
                    val files =
                        when (val r = diffs.files(repository, target, options)) {
                            is Result.Success -> r.data

                            is Result.Failure -> {
                                setState {
                                    copy(
                                        commit = center.commit,
                                        files = emptyList(),
                                        index = -1,
                                        body = DiffBody.Failed(r.error),
                                    )
                                }
                                return@launch
                            }
                        }
                    val subject = (
                        contents.message(
                            center.commit,
                        ) as? Result.Success
                        )?.data?.substringBefore('\n').orEmpty()
                    setState { copy(commit = center.commit, subject = subject, files = files) }
                }
                val index = currentState.files.indexOfFirst { it.path == center.path }
                setState { copy(index = index) }
                val file = currentState.files.getOrNull(index) ?: return@launch
                val loaded = body(file, target, options)
                setState { copy(body = loaded) }
            }
    }

    private suspend fun body(file: ChangedFile, target: DiffTarget, options: DiffOptions): DiffBody {
        if (file.isSubmodule) return DiffBody.Submodule(file.oldObject, file.newObject)
        if (file.isBinary) return binary(file)
        if (!anyway) {
            val size = largest(file)
            if (size != null && size > limit) return DiffBody.TooLarge(size)
        }
        val fileDiff =
            when (val r = diffs.diff(repository, target, options, paths = listOf(file.path))) {
                is Result.Success -> r.data.firstOrNull { it.file.path == file.path } ?: r.data.firstOrNull()
                is Result.Failure -> return DiffBody.Failed(r.error)
            }
        val hunks = fileDiff?.hunks.orEmpty()
        if (hunks.isEmpty()) {
            if (file.modeChanged) return DiffBody.ModeOnly(file.oldMode, file.newMode)
            file.oldPath?.let { return DiffBody.Renamed(it) }
        }
        return DiffBody.Text(DiffDocument(file.path, hunks.map(::hunkView)))
    }

    /** The bigger side's size, from its object (the text diff needs no read; this is only the inline limit's check). */
    private suspend fun largest(file: ChangedFile): Long? {
        var max: Long? = null
        for (id in listOfNotNull(file.oldObject, file.newObject)) {
            val size =
                when (val c = (contents.read(id, limit) as? Result.Success)?.data) {
                    is FileContent.TooLarge -> c.size
                    is FileContent.Text -> c.text.length.toLong()
                    is FileContent.Binary -> c.size
                    else -> null
                }
            if (size != null) max = maxOf(max ?: 0, size)
        }
        return max
    }

    private suspend fun binary(file: ChangedFile): DiffBody {
        suspend fun size(id: ObjectId?): Long? = id?.let {
            when (val c = (contents.read(it, limit) as? Result.Success)?.data) {
                is FileContent.Binary -> c.size
                is FileContent.TooLarge -> c.size
                is FileContent.Text -> c.text.length.toLong()
                else -> null
            }
        }
        val old = size(file.oldObject)
        val new = size(file.newObject)
        val isImage = file.path.substringAfterLast('.', "").lowercase() in IMAGE_TYPES
        val images =
            if (isImage && (old ?: 0) <= limit && (new ?: 0) <= limit) {
                val oldBytes = file.oldObject?.let { (contents.readBytes(it, limit) as? Result.Success)?.data }
                val newBytes = file.newObject?.let { (contents.readBytes(it, limit) as? Result.Success)?.data }
                ImagePair(oldBytes, newBytes).takeIf { oldBytes != null || newBytes != null }
            } else {
                null
            }
        return DiffBody.Binary(old, new, images)
    }

    override fun onCleared() {
        if (contentsOpen) contents.close()
        super.onCleared()
    }

    internal companion object {
        /** Formats the image diff draws (Skia decodes them). */
        val IMAGE_TYPES = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "ico")
    }
}

/** A data-layer hunk as the viewer's model: git's header with its section, and the lines with their numbers. */
internal fun hunkView(hunk: Hunk): DiffHunkView {
    val header =
        "@@ -${hunk.oldStart},${hunk.oldCount} +${hunk.newStart},${hunk.newCount} @@" +
            if (hunk.section.isNotEmpty()) " ${hunk.section}" else ""
    return DiffHunkView(header, hunk.lines.map(::lineView))
}

private fun lineView(line: DiffLine): DiffLineView = DiffLineView(
    when (line.kind) {
        DiffLine.Kind.ADDED -> DiffLineKind.ADDED
        DiffLine.Kind.REMOVED -> DiffLineKind.REMOVED
        DiffLine.Kind.CONTEXT -> DiffLineKind.CONTEXT
    },
    line.oldNumber,
    line.newNumber,
    line.text,
)
