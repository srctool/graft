// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.presentation.changes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.button.CamoButton
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.skeleton.CamoSkeleton
import com.srctool.camouflage.core.text.CamoText
import com.srctool.camouflage.core.theme.Appearance
import com.srctool.graft.core.exception.CommandOutput
import com.srctool.graft.core.ui.git.DiffDocument
import com.srctool.graft.core.ui.git.DiffHunkView
import com.srctool.graft.core.ui.git.DiffLineKind
import com.srctool.graft.core.ui.git.DiffLineView
import com.srctool.graft.core.ui.git.DiffMode
import com.srctool.graft.core.ui.git.DiffSelection
import com.srctool.graft.core.ui.git.DiffViewer
import com.srctool.graft.core.ui.git.rememberDiffViewerState
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.data.git.api.models.DiffLine
import com.srctool.graft.data.git.api.models.FileDiff
import com.srctool.graft.data.git.api.models.Hunk
import com.srctool.graft.feature.workingcopy.domain.api.models.DiffSide
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingDiff

/** The diff of the file open in the Changes panel, in the center (Task 12 wireframe), with the action bar under it. */
@Composable
internal fun WorkingDiffPage(viewModel: ChangesViewModel) {
    val state by viewModel.state.collectAsState()
    WorkingDiffContent(state, viewModel::onEvent)
}

/** The diff without its view model, for screenshot tests. */
@Composable
internal fun WorkingDiffContent(state: ChangesState, onEvent: (ChangesEvent) -> Unit) {
    val colors = Camouflage.colors
    val open = state.open
    val focus = remember { FocusRequester() }
    val viewer = rememberDiffViewerState()
    var mode by remember { mutableStateOf(DiffMode.UNIFIED) }
    val ready = (state.body as? DiffBodyState.Ready)?.diff
    LaunchedEffect(open?.row?.path, ready is WorkingDiff.Text) {
        if (ready is WorkingDiff.Text) runCatching { focus.requestFocus() }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.surface)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val mod = event.isMetaPressed || event.isCtrlPressed
                val handled: ChangesEvent =
                    when {
                        event.key == Key.Escape -> ChangesEvent.DiffClosed
                        event.isAltPressed && !mod && event.key == Key.DirectionLeft -> ChangesEvent.DiffClosed
                        mod && event.isAltPressed && event.key == Key.Z -> ChangesEvent.TicksDiscardAsked
                        mod && event.key == Key.Enter -> ChangesEvent.TicksApplied
                        else -> return@onPreviewKeyEvent false
                    }
                onEvent(handled)
                true
            },
    ) {
        Breadcrumb(state, ready as? WorkingDiff.Text, mode, onEvent) { mode = it }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val body = state.body) {
                null, DiffBodyState.Loading ->
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(10) { CamoSkeleton(10.dp, width = (180 + (it * 53) % 240).dp) }
                    }

                is DiffBodyState.Failed -> Info(failureText(body.error)) {
                    CamoButton(graftText("changes.retry", "Retry"), onClick = {
                        onEvent(ChangesEvent.Retry)
                    }, modifier = Modifier.height(Graft.tokens.density.control))
                    if (body.error is CommandOutput) {
                        CamoButton(
                            graftText("graph.showOutput", "Show output"),
                            onClick = { onEvent(ChangesEvent.OutputShown(true)) },
                            modifier = Modifier.height(Graft.tokens.density.control),
                            appearance = Appearance.Ghost,
                        )
                    }
                }

                is DiffBodyState.Ready -> when (val diff = body.diff) {
                    is WorkingDiff.Text ->
                        DiffViewer(
                            toDocument(diff.diff),
                            Modifier.fillMaxSize().focusRequester(focus),
                            mode = mode,
                            state = viewer,
                            selection = DiffSelection(
                                selected = state.ticked,
                                onToggleHunk = { onEvent(ChangesEvent.HunkTicked(it)) },
                                onToggleLine = { onEvent(ChangesEvent.LineTicked(it)) },
                            ),
                        )

                    is WorkingDiff.Binary -> Info(
                        graftText(
                            "changes.diff.binary",
                            "Binary file{size}.",
                            "size" to (diff.size?.let { ", ${bytes(it)}" } ?: ""),
                        ),
                    )

                    is WorkingDiff.TooLarge -> Info(
                        graftText(
                            "diff.tooLarge",
                            "This file is over the {size} inline limit.",
                            "size" to bytes(diff.size),
                        ),
                    ) {
                        CamoButton(
                            graftText("changes.showAnyway", "Show diff anyway"),
                            onClick = { onEvent(ChangesEvent.ShowAnyway) },
                            modifier = Modifier.height(Graft.tokens.density.control),
                        )
                    }

                    is WorkingDiff.Renamed -> Info(
                        graftText(
                            "diff.renamed",
                            "Renamed from {from}, no changes.",
                            "from" to diff.from,
                        ),
                    )

                    is WorkingDiff.ModeOnly ->
                        Info(
                            graftText(
                                "diff.mode",
                                "File mode changed: {from} → {to}.",
                                "from" to diff.from,
                                "to" to diff.to,
                            ),
                        )

                    is WorkingDiff.Submodule -> Info(
                        graftText("changes.diff.submodule", "Submodule. Open it to see what changed inside."),
                    )

                    WorkingDiff.Nothing -> Info(nothingText(open?.side, open?.row?.name.orEmpty())) {
                        CamoButton(
                            graftText("diff.back", "Back to the graph"),
                            onClick = { onEvent(ChangesEvent.DiffClosed) },
                            modifier = Modifier.height(Graft.tokens.density.control),
                        )
                    }
                }
            }
        }
        if (open != null && ready is WorkingDiff.Text && state.ticked.isNotEmpty()) ActionBar(state, open.side, onEvent)
    }
}

@Composable
private fun nothingText(side: DiffSide?, name: String): String = if (side == DiffSide.STAGED) {
    graftText("changes.diff.nothingStaged", "{name} has no staged changes.", "name" to name)
} else {
    graftText("changes.diff.nothingUnstaged", "{name} has no unstaged changes.", "name" to name)
}

@Composable
private fun Breadcrumb(
    state: ChangesState,
    text: WorkingDiff.Text?,
    mode: DiffMode,
    onEvent: (ChangesEvent) -> Unit,
    onMode: (DiffMode) -> Unit,
) {
    val colors = Camouflage.colors
    val density = Graft.tokens.density
    val open = state.open
    val backLabel = graftText("diff.back", "Back to the graph")
    val side = if (open?.side ==
        DiffSide.STAGED
    ) {
        graftText("changes.staged", "Staged")
    } else {
        graftText("changes.unstaged", "Unstaged")
    }
    Row(
        Modifier.fillMaxWidth().height(
            density.control + 10.dp,
        ).background(colors.surfaceContainerLow).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CamoButton(
            graftText("diff.graph", "Graph"),
            onClick = { onEvent(ChangesEvent.DiffClosed) },
            modifier = Modifier.height(density.control).semantics { contentDescription = backLabel },
            appearance = Appearance.Ghost,
        )
        CamoText(
            "›  ${graftText("changes.title", "Changes")}  ›  $side  ›  ${open?.row?.path.orEmpty()}",
            style = Graft.tokens.denseStrong,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (text != null) {
            val hunks = text.diff.hunks.size
            CamoText(
                graftText("changes.diff.hunks", "{count} hunks", "count" to hunks),
                style = Graft.tokens.dense,
                color = colors.onSurfaceVariant,
            )
            CamoButton(
                if (mode ==
                    DiffMode.UNIFIED
                ) {
                    graftText("diff.split", "Side by side")
                } else {
                    graftText("diff.unified", "Unified")
                },
                onClick = { onMode(if (mode == DiffMode.UNIFIED) DiffMode.SIDE_BY_SIDE else DiffMode.UNIFIED) },
                modifier = Modifier.height(density.control),
                appearance = Appearance.Ghost,
            )
        }
    }
}

@Composable
private fun ActionBar(state: ChangesState, side: DiffSide, onEvent: (ChangesEvent) -> Unit) {
    val colors = Camouflage.colors
    val density = Graft.tokens.density
    val count = state.ticked.size
    val enabled = state.busy == null
    val height = Modifier.height(density.control)
    Row(
        Modifier.fillMaxWidth().background(colors.surfaceContainerLow).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CamoText(
            graftText("changes.ticked", "{count} lines ticked", "count" to count),
            style = Graft.tokens.dense,
            modifier = Modifier.weight(1f),
        )
        CamoButton(
            if (side == DiffSide.UNSTAGED) {
                graftText("changes.stageLines", "Stage {count} lines", "count" to count)
            } else {
                graftText("changes.unstageLines", "Unstage {count} lines", "count" to count)
            },
            onClick = { if (enabled) onEvent(ChangesEvent.TicksApplied) },
            modifier = height,
        )
        if (side == DiffSide.UNSTAGED) {
            CamoButton(
                graftText("changes.discardLines", "Discard {count} lines…", "count" to count),
                onClick = { if (enabled) onEvent(ChangesEvent.TicksDiscardAsked) },
                modifier = height,
                appearance = Appearance.Ghost,
            )
        }
        CamoButton(graftText("changes.clear", "Clear"), onClick = {
            onEvent(ChangesEvent.TicksCleared)
        }, modifier = height, appearance = Appearance.Ghost)
    }
}

@Composable
private fun Info(text: String, actions: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CamoText(text, style = Graft.tokens.dense, color = Camouflage.colors.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}

private fun bytes(size: Long): String = when {
    size < KB -> "$size B"
    size < KB * KB -> "%.1f KB".format(size / KB.toDouble())
    else -> "%.1f MB".format(size / (KB * KB).toDouble())
}

/** A data-layer diff as the viewer's document: git's hunk headers with their section, and the lines with numbers. */
internal fun toDocument(file: FileDiff): DiffDocument = DiffDocument(file.file.path, file.hunks.map(::hunkView))

private fun hunkView(hunk: Hunk): DiffHunkView {
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

private const val KB = 1024L
