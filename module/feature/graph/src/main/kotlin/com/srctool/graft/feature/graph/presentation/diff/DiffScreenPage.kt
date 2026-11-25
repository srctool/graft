// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.diff

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.button.CamoButton
import com.srctool.camouflage.core.checkbox.CamoCheckbox
import com.srctool.camouflage.core.dialog.CamoDialog
import com.srctool.camouflage.core.dialog.CamoDialogFrame
import com.srctool.camouflage.core.icon.CamoIcon
import com.srctool.camouflage.core.iconbutton.CamoIconButton
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.skeleton.CamoSkeleton
import com.srctool.camouflage.core.text.CamoText
import com.srctool.camouflage.core.theme.Appearance
import com.srctool.graft.core.exception.CommandOutput
import com.srctool.graft.core.ui.git.DiffMode
import com.srctool.graft.core.ui.git.DiffViewer
import com.srctool.graft.core.ui.git.rememberDiffViewerState
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.core.ui.theme.GraftIcon
import com.srctool.graft.core.ui.theme.graftIcon
import com.srctool.graft.data.git.api.models.DiffTarget
import com.srctool.graft.feature.graph.presentation.details.StatusBadge
import com.srctool.graft.presentation.view.CenterView
import com.srctool.sinew.exception.displayMessage
import com.srctool.sinew.models.SinewException

@Composable
internal fun DiffScreenPage(viewModel: DiffScreenViewModel) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) { viewModel.onEvent(DiffScreenEvent.Opened) }
    DiffScreenContent(state, viewModel::onEvent)
}

/** The diff screen without its view model, for screenshot tests. */
@Composable
internal fun DiffScreenContent(state: DiffScreenState, onEvent: (DiffScreenEvent) -> Unit) {
    val colors = Camouflage.colors
    val focus = remember { FocusRequester() }
    val viewer = rememberDiffViewerState()
    LaunchedEffect(state.body) { if (state.body is DiffBody.Text) runCatching { focus.requestFocus() } }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.surface)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val mod = event.isMetaPressed || event.isCtrlPressed
                val altShift = event.isAltPressed && event.isShiftPressed
                val handled: DiffScreenEvent =
                    when {
                        event.key == Key.Escape -> DiffScreenEvent.Back
                        altShift && event.key == Key.DirectionRight -> DiffScreenEvent.StepFile(+1)
                        altShift && event.key == Key.DirectionLeft -> DiffScreenEvent.StepFile(-1)
                        event.isAltPressed && event.key == Key.DirectionLeft -> DiffScreenEvent.Back
                        mod && event.isAltPressed && event.key == Key.D -> DiffScreenEvent.ModeToggled
                        else -> return@onPreviewKeyEvent false
                    }
                onEvent(handled)
                true
            },
    ) {
        Breadcrumb(state, onEvent)
        Toolbar(state, viewer.currentChange, viewer.changeCount, onEvent)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val body = state.body) {
                DiffBody.Loading ->
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(10) { CamoSkeleton(10.dp, width = (180 + (it * 53) % 240).dp) }
                    }

                is DiffBody.Text ->
                    DiffViewer(
                        body.document,
                        Modifier.fillMaxSize().focusRequester(focus),
                        mode = state.mode,
                        state = viewer,
                    )

                is DiffBody.Binary -> BinaryBody(body)

                is DiffBody.TooLarge ->
                    Message(
                        graftText(
                            "diff.tooLarge",
                            "This file is over the {size} inline limit.",
                            "size" to bytes(body.size),
                        ),
                    ) {
                        CamoButton(
                            graftText("diff.showAnyway", "Show anyway"),
                            onClick = { onEvent(DiffScreenEvent.ShowAnyway) },
                            modifier = Modifier.height(Graft.tokens.density.formControl),
                        )
                    }

                is DiffBody.Renamed -> Message(
                    graftText(
                        "diff.renamed",
                        "Renamed from {from}, no changes.",
                        "from" to body.from,
                    ),
                )

                is DiffBody.ModeOnly ->
                    Message(
                        graftText(
                            "diff.mode",
                            "File mode changed: {from} → {to}.",
                            "from" to body.from,
                            "to" to body.to,
                        ),
                    )

                is DiffBody.Submodule ->
                    Message(
                        graftText(
                            "diff.submodule",
                            "Submodule moved: {from} → {to}.",
                            "from" to (body.from?.short() ?: "—"),
                            "to" to (body.to?.short() ?: "—"),
                        ),
                    )

                is DiffBody.Failed -> Failed(body.error, onEvent)
            }
        }
    }
    if (state.outputShown) OutputDialog((state.body as? DiffBody.Failed)?.error, onEvent)
}

@Composable
private fun Breadcrumb(state: DiffScreenState, onEvent: (DiffScreenEvent) -> Unit) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val density = tokens.density
    val back =
        if (state.back is CenterView.Compare) {
            graftText("diff.backCompare", "Back to Compare")
        } else {
            graftText("diff.back", "Back to the graph")
        }
    Row(
        Modifier
            .fillMaxWidth()
            .height(density.control + 10.dp)
            .background(colors.surfaceContainerLow)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val toCompare = state.back is CenterView.Compare
        CamoButton(
            if (toCompare) graftText("diff.compare", "Compare") else graftText("diff.graph", "Graph"),
            onClick = { onEvent(DiffScreenEvent.Back) },
            modifier = Modifier.height(density.control).semantics { contentDescription = back },
            appearance = Appearance.Ghost,
            prefixIcon = {
                CamoIcon(
                    graftIcon(GraftIcon.CHEVRON_RIGHT),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp).graphicsFlip(),
                )
            },
        )
        val what =
            when (val target = state.target) {
                is DiffTarget.Commit -> "${target.id.short()} ${state.subject}"

                // Compare's diffs: git's own notation names them.
                is DiffTarget.Since -> "${shortRev(target.base)}...${shortRev(target.tip)}"

                is DiffTarget.Between -> "${shortRev(target.from)} ${shortRev(target.to)}"

                // The working tree's diffs are drawn by the Changes panel's own center view, never this screen.
                DiffTarget.Staged, DiffTarget.Unstaged -> null

                null -> null
            }
        if (what != null) {
            CamoText("›", style = tokens.dense, color = colors.onSurfaceVariant)
            CamoText(
                what,
                style = tokens.dense,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 280.dp),
            )
        }
        state.file?.let {
            CamoText("›", style = tokens.dense, color = colors.onSurfaceVariant)
            CamoText(
                it.path,
                style = tokens.code.copy(fontSize = tokens.dense.fontSize),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } ?: Spacer(Modifier.weight(1f))
        if (state.files.size > 1 && state.index >= 0) {
            val previous = graftText("diff.previousFile", "Previous file")
            val next = graftText("diff.nextFile", "Next file")
            val position =
                graftText(
                    "diff.fileOf",
                    "File {index} of {total}, {path}",
                    "index" to state.index + 1,
                    "total" to state.files.size,
                    "path" to (state.file?.path ?: ""),
                )
            StepButton(GraftIcon.CHEVRON_RIGHT, previous, state.index > 0, flipped = true) {
                onEvent(DiffScreenEvent.StepFile(-1))
            }
            CamoText(
                graftText("diff.of", "{index} of {total}", "index" to state.index + 1, "total" to state.files.size),
                modifier = Modifier.semantics { contentDescription = position },
                style = tokens.dense,
                color = colors.onSurfaceVariant,
            )
            StepButton(GraftIcon.CHEVRON_RIGHT, next, state.index < state.files.lastIndex) {
                onEvent(DiffScreenEvent.StepFile(+1))
            }
        }
    }
}

@Composable
private fun StepButton(
    icon: GraftIcon,
    label: String,
    enabled: Boolean,
    flipped: Boolean = false,
    onClick: () -> Unit,
) {
    CamoIconButton(
        icon = {
            CamoIcon(
                graftIcon(icon),
                contentDescription = null,
                modifier = Modifier.size(14.dp).then(if (flipped) Modifier.graphicsFlip() else Modifier),
            )
        },
        contentDescription = label,
        onClick = { if (enabled) onClick() },
        modifier = Modifier.size(Graft.tokens.density.control),
        appearance = Appearance.Ghost,
        tooltip = label,
    )
}

private fun Modifier.graphicsFlip(): Modifier = this.scale(scaleX = -1f, scaleY = 1f)

@Composable
private fun Toolbar(state: DiffScreenState, change: Int, changes: Int, onEvent: (DiffScreenEvent) -> Unit) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val density = tokens.density
    val file = state.file
    Row(
        Modifier.fillMaxWidth().height(density.control + 10.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (file != null) {
            StatusBadge(file.status)
            if (!file.isBinary) {
                CamoText("+${file.additions ?: 0}", style = tokens.dense, color = colors.success)
                CamoText("−${file.deletions ?: 0}", style = tokens.dense, color = colors.error)
            }
        }
        Spacer(Modifier.width(8.dp))
        ModeButton(graftText("diff.unified", "Unified"), state.mode == DiffMode.UNIFIED) {
            onEvent(DiffScreenEvent.ModeChosen(DiffMode.UNIFIED))
        }
        ModeButton(graftText("diff.sideBySide", "Side by side"), state.mode == DiffMode.SIDE_BY_SIDE) {
            onEvent(DiffScreenEvent.ModeChosen(DiffMode.SIDE_BY_SIDE))
        }
        CamoCheckbox(
            state.ignoreWhitespace,
            onCheckedChange = { onEvent(DiffScreenEvent.WhitespaceToggled) },
            label = graftText("diff.ignoreWhitespace", "Ignore whitespace"),
            modifier = Modifier.height(density.control),
        )
        Spacer(Modifier.weight(1f))
        if (state.body is DiffBody.Text && changes > 0) {
            CamoText(
                graftText(
                    "diff.changes",
                    "changes {current}/{total}",
                    "current" to maxOf(change + 1, 0),
                    "total" to changes,
                ),
                style = tokens.dense,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModeButton(label: String, on: Boolean, onClick: () -> Unit) {
    CamoButton(
        label,
        onClick = onClick,
        modifier = Modifier.height(Graft.tokens.density.control),
        appearance = if (on) Appearance.Outline else Appearance.Ghost,
    )
}

@Composable
private fun Message(text: String, action: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CamoText(text, style = Graft.tokens.dense, color = Camouflage.colors.onSurfaceVariant)
            action?.invoke()
        }
    }
}

@Composable
private fun BinaryBody(body: DiffBody.Binary) {
    val text =
        graftText(
            "diff.binary",
            "Binary file, {from} → {to}.",
            "from" to (body.oldSize?.let(::bytes) ?: "—"),
            "to" to (body.newSize?.let(::bytes) ?: "—"),
        )
    val images = body.images
    if (images == null) {
        Message(text)
        return
    }
    val old = remember(images) { images.old?.let(::decode) }
    val new = remember(images) { images.new?.let(::decode) }
    if (old == null && new == null) {
        Message(text)
        return
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CamoText(text, style = Graft.tokens.dense, color = Camouflage.colors.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ImageSide(graftText("diff.before", "Before"), old)
            ImageSide(graftText("diff.after", "After"), new)
        }
    }
}

@Composable
private fun ImageSide(label: String, image: ImageBitmap?) {
    val colors = Camouflage.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CamoText(label, style = Graft.tokens.denseStrong, color = colors.onSurfaceVariant)
        Box(
            Modifier.widthIn(
                min = 120.dp,
                max = 420.dp,
            ).heightIn(min = 80.dp, max = 420.dp).border(1.dp, colors.outlineVariant).padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (image == null) {
                CamoText("—", style = Graft.tokens.dense, color = colors.onSurfaceVariant)
            } else {
                Image(image, contentDescription = label)
            }
        }
    }
}

/** An image's bytes as a bitmap, or null when Skia can't read them (an unknown or broken file). */
private fun decode(bytes: ByteArray): ImageBitmap? =
    runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

private fun bytes(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
    else -> "%.1f MB".format(size / (1024.0 * 1024.0))
}

@Composable
private fun Failed(error: Throwable, onEvent: (DiffScreenEvent) -> Unit) {
    val message = (error as? SinewException)?.displayMessage(Graft.localizer) ?: error.message.orEmpty()
    val code = (error as? SinewException)?.code
    Message(if (code != null && code !in message) "$message · $code" else message) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val height = Modifier.height(Graft.tokens.density.control)
            CamoButton(graftText("diff.retry", "Retry"), onClick = {
                onEvent(DiffScreenEvent.Retry)
            }, modifier = height)
            if (error is CommandOutput) {
                CamoButton(
                    graftText("graph.showOutput", "Show output"),
                    onClick = { onEvent(DiffScreenEvent.OutputShown(true)) },
                    modifier = height,
                    appearance = Appearance.Ghost,
                )
            }
        }
    }
}

@Composable
private fun OutputDialog(error: Throwable?, onEvent: (DiffScreenEvent) -> Unit) {
    val output = error as? CommandOutput ?: return
    val close = { onEvent(DiffScreenEvent.OutputShown(false)) }
    CamoDialogFrame(onDismissRequest = close) {
        CamoDialog(
            message = output.argv.joinToString(" "),
            title = graftText("graph.output.title", "Git output"),
            confirm = { CamoButton(graftText("graph.output.close", "Close"), onClick = close) },
        ) {
            BasicText(output.stderr, style = Graft.tokens.code.copy(color = Camouflage.colors.onSurface))
        }
    }
}

/** A revision as the breadcrumb shows it: a ref without its namespace, a commit id as 7 characters. */
internal fun shortRev(rev: String): String = when {
    rev.startsWith("refs/heads/") -> rev.removePrefix("refs/heads/")
    rev.startsWith("refs/remotes/") -> rev.removePrefix("refs/remotes/")
    rev.startsWith("refs/tags/") -> rev.removePrefix("refs/tags/")
    rev.length >= 40 && rev.all { it in '0'..'9' || it in 'a'..'f' } -> rev.take(7)
    else -> rev
}
