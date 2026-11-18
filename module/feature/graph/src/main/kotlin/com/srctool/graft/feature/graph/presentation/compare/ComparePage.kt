// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.button.CamoButton
import com.srctool.camouflage.core.dialog.CamoDialog
import com.srctool.camouflage.core.dialog.CamoDialogFrame
import com.srctool.camouflage.core.icon.CamoIcon
import com.srctool.camouflage.core.iconbutton.CamoIconButton
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.skeleton.CamoSkeleton
import com.srctool.camouflage.core.text.CamoText
import com.srctool.camouflage.core.theme.Appearance
import com.srctool.graft.core.exception.CommandOutput
import com.srctool.graft.core.ui.components.CommandPalette
import com.srctool.graft.core.ui.components.PaletteGroup
import com.srctool.graft.core.ui.components.PaletteItem
import com.srctool.graft.core.ui.components.SpeedSearchMatcher
import com.srctool.graft.core.ui.git.AuthorInitials
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.core.ui.theme.GraftIcon
import com.srctool.graft.core.ui.theme.graftIcon
import com.srctool.graft.feature.graph.presentation.details.FileRow
import com.srctool.graft.feature.graph.presentation.details.StatusBadge
import com.srctool.graft.feature.graph.presentation.details.fileLabel
import com.srctool.graft.feature.graph.presentation.diff.shortRev
import com.srctool.graft.feature.graph.presentation.graph.Age
import com.srctool.graft.feature.graph.presentation.graph.age
import com.srctool.sinew.exception.displayMessage
import com.srctool.sinew.models.SinewException
import java.text.NumberFormat

@Composable
internal fun ComparePage(viewModel: CompareViewModel) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) { viewModel.onEvent(CompareEvent.Opened) }
    CompareContent(state, viewModel::onEvent)
}

/** Compare without its view model, for screenshot tests. */
@Composable
internal fun CompareContent(state: CompareState, onEvent: (CompareEvent) -> Unit) {
    val colors = Camouflage.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.surface)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || state.picker != null) return@onPreviewKeyEvent false
                val back = event.key == Key.Escape || (event.isAltPressed && event.key == Key.DirectionLeft)
                if (back) onEvent(CompareEvent.Back)
                back
            },
    ) {
        Breadcrumb(onEvent)
        Pickers(state, onEvent)
        when (state.phase) {
            CompareState.Phase.Picking ->
                Note(
                    graftText(
                        "compare.pick",
                        "Pick a branch, tag or commit to compare with {a}.",
                        "a" to shortRev(state.a ?: ""),
                    ),
                )

            CompareState.Phase.Loading ->
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(6) { CamoSkeleton(10.dp, width = (200 + it * 41).dp) }
                }

            CompareState.Phase.NotFound ->
                Note(graftText("compare.notFound", "Couldn't find \"{name}\".", "name" to (state.missing ?: "")))

            CompareState.Phase.Same ->
                Note(
                    graftText(
                        "compare.same",
                        "{a} and {b} point to the same commit.",
                        "a" to shortRev(state.a ?: ""),
                        "b" to shortRev(state.b ?: ""),
                    ),
                )

            CompareState.Phase.Failed -> Failed(state.error, onEvent)

            CompareState.Phase.Ready -> Ready(state, onEvent)
        }
    }
    state.picker?.let { side -> Picker(state, side, onEvent) }
    if (state.outputShown) OutputDialog(state.error, onEvent)
}

@Composable
private fun Breadcrumb(onEvent: (CompareEvent) -> Unit) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val back = graftText("diff.back", "Back to the graph")
    Row(
        Modifier.fillMaxWidth().height(
            tokens.density.control + 10.dp,
        ).background(colors.surfaceContainerLow).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CamoButton(
            graftText("diff.graph", "Graph"),
            onClick = { onEvent(CompareEvent.Back) },
            modifier = Modifier.height(tokens.density.control).semantics { contentDescription = back },
            appearance = Appearance.Ghost,
            prefixIcon = {
                CamoIcon(
                    graftIcon(GraftIcon.CHEVRON_RIGHT),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp).scale(-1f, 1f),
                )
            },
        )
        CamoText("›", style = tokens.dense, color = colors.onSurfaceVariant)
        CamoText(graftText("compare.title", "Compare"), style = tokens.dense, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun Pickers(state: CompareState, onEvent: (CompareEvent) -> Unit) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val density = tokens.density
    val label =
        graftText(
            "compare.label",
            "Compare {a} with {b}",
            "a" to shortRev(state.a ?: ""),
            "b" to shortRev(state.b ?: "…"),
        )
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.semantics { contentDescription = label },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CamoText("A", style = tokens.denseStrong, color = colors.onSurfaceVariant)
            PickerButton(state.a, graftText("compare.pickA", "Choose A")) { onEvent(CompareEvent.PickerOpened(Side.A)) }
            val swap = graftText("compare.swap", "Swap A and B")
            CamoIconButton(
                icon = {
                    CamoIcon(
                        graftIcon(GraftIcon.COMPARE),
                        contentDescription = null,
                        modifier = Modifier.size(density.icon),
                    )
                },
                contentDescription = swap,
                onClick = { onEvent(CompareEvent.Swapped) },
                modifier = Modifier.size(density.control),
                appearance = Appearance.Ghost,
                tooltip = swap,
            )
            CamoText("B", style = tokens.denseStrong, color = colors.onSurfaceVariant)
            PickerButton(state.b, graftText("compare.pickB", "Choose B")) { onEvent(CompareEvent.PickerOpened(Side.B)) }
        }
        val summary = state.summary
        if (state.phase == CompareState.Phase.Ready && summary != null) {
            // The summary ran on commit ids; the names are the ones picked.
            val aName = shortRev(state.a ?: summary.base)
            val bName = shortRev(state.b ?: summary.compare)
            val line =
                when {
                    summary.mergeBase == null -> graftText(
                        "compare.unrelated",
                        "{b} and {a} share no history.",
                        "a" to aName,
                        "b" to bName,
                    )

                    summary.ahead == 0 && summary.behind == 0 ->
                        graftText("compare.equal", "{b} and {a} have the same history.", "a" to aName, "b" to bName)

                    else ->
                        graftText(
                            "compare.summary",
                            "{b} is {ahead} ahead and {behind} behind {a}",
                            "a" to aName,
                            "b" to bName,
                            "ahead" to NumberFormat.getIntegerInstance().format(summary.ahead),
                            "behind" to NumberFormat.getIntegerInstance().format(summary.behind),
                        )
                }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CamoText(line, modifier = Modifier.semantics { heading() }, style = tokens.denseStrong)
                summary.mergeBase?.let {
                    CamoText(
                        "·  " + graftText("compare.mergeBase", "merge base {id}", "id" to it.short()),
                        style = tokens.dense,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            Preview(state, aName, summary.ahead)
            if (summary.mergeBase != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    KindButton(
                        graftText("compare.changes", "Changes B introduces"),
                        state.kind == CompareKind.CHANGES,
                    ) {
                        onEvent(CompareEvent.KindChosen(CompareKind.CHANGES))
                    }
                    KindButton(graftText("compare.direct", "Direct difference"), state.kind == CompareKind.DIRECT) {
                        onEvent(CompareEvent.KindChosen(CompareKind.DIRECT))
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerButton(revision: String?, placeholder: String, onClick: () -> Unit) {
    val icon =
        when {
            revision == null -> GraftIcon.ADD
            revision.startsWith("refs/tags/") -> GraftIcon.TAG
            revision.startsWith("refs/remotes/") -> GraftIcon.CLOUD
            revision.startsWith("refs/") -> GraftIcon.BRANCH
            else -> GraftIcon.COMMIT
        }
    CamoButton(
        revision?.let(::shortRev) ?: placeholder,
        onClick = onClick,
        modifier = Modifier.height(Graft.tokens.density.control),
        appearance = Appearance.Outline,
        prefixIcon = { CamoIcon(graftIcon(icon), contentDescription = null, modifier = Modifier.size(14.dp)) },
        suffixIcon = {
            CamoIcon(graftIcon(GraftIcon.CHEVRON_DOWN), contentDescription = null, modifier = Modifier.size(12.dp))
        },
    )
}

@Composable
private fun KindButton(label: String, on: Boolean, onClick: () -> Unit) {
    CamoButton(
        label,
        onClick = onClick,
        modifier = Modifier.height(Graft.tokens.density.control),
        appearance = if (on) Appearance.Outline else Appearance.Ghost,
    )
}

@Composable
private fun Preview(state: CompareState, into: String, ahead: Int) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val preview = state.preview
    val (icon, color, text) =
        when {
            ahead == 0 -> Triple(
                GraftIcon.INFORMATION,
                colors.onSurfaceVariant,
                graftText("compare.nothingToMerge", "Nothing to merge"),
            )

            state.previewLoading || preview == null ->
                Triple(GraftIcon.TIME, colors.onSurfaceVariant, graftText("compare.checking", "Checking the merge…"))

            preview.clean -> Triple(
                GraftIcon.CHECKMARK,
                colors.success,
                graftText(
                    "compare.clean",
                    "Merges cleanly into {a}",
                    "a" to into,
                ),
            )

            else -> {
                val shown = preview.conflicts.take(5).joinToString(", ")
                val more = preview.conflicts.size - 5
                Triple(
                    GraftIcon.WARNING,
                    colors.error,
                    graftText(
                        "compare.conflicts",
                        "{count} files would conflict: {files}",
                        "count" to preview.conflicts.size,
                        "files" to (if (more > 0) "$shown +$more" else shown),
                    ),
                )
            }
        }
    Row(
        Modifier.clearAndSetSemantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CamoIcon(graftIcon(icon), contentDescription = null, modifier = Modifier.size(14.dp), tint = color)
        CamoText(text, style = tokens.dense, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Ready(state: CompareState, onEvent: (CompareEvent) -> Unit) {
    val colors = Camouflage.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val listLabel = graftText("compare.lists", "Commits and files")
    Row(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !focused) return@onPreviewKeyEvent false
                val handled: CompareEvent =
                    when (event.key) {
                        Key.DirectionDown -> CompareEvent.Moved(1)
                        Key.DirectionUp -> CompareEvent.Moved(-1)
                        Key.DirectionRight -> CompareEvent.Expand
                        Key.DirectionLeft -> CompareEvent.Collapse
                        Key.Enter -> CompareEvent.Activated(state.focus ?: return@onPreviewKeyEvent false)
                        else -> return@onPreviewKeyEvent false
                    }
                onEvent(handled)
                true
            }.focusable(interactionSource = interaction)
            .semantics { contentDescription = listLabel },
    ) {
        val a = shortRev(state.a ?: "")
        val b = shortRev(state.b ?: "")
        val summary = state.summary
        val moreB = more(summary?.ahead ?: 0, state.onlyInB.size)
        val moreA = more(summary?.behind ?: 0, state.onlyInA.size)
        LazyColumn(Modifier.weight(1f).fillMaxHeight().background(colors.surface)) {
            item(key = "in-b") {
                SectionHeader(
                    graftText("compare.inB", "In {b}, not in {a}", "a" to a, "b" to b),
                    graftText(
                        "compare.inBLabel",
                        "Commits in {b} and not in {a}, {count}",
                        "a" to a,
                        "b" to b,
                        "count" to (summary?.ahead ?: 0),
                    ),
                    summary?.ahead ?: 0,
                )
            }
            items(state.onlyInB, key = { "b-" + it.row.id.hex }) { CommitLine(it, state, focused, onEvent) }
            moreB?.let { text -> item(key = "more-b") { MoreLine(text) } }
            item(key = "in-a") {
                SectionHeader(
                    graftText("compare.inA", "In {a}, not in {b}", "a" to a, "b" to b),
                    graftText(
                        "compare.inALabel",
                        "Commits in {a} and not in {b}, {count}",
                        "a" to a,
                        "b" to b,
                        "count" to (summary?.behind ?: 0),
                    ),
                    summary?.behind ?: 0,
                )
            }
            items(state.onlyInA, key = { "a-" + it.row.id.hex }) { CommitLine(it, state, focused, onEvent) }
            moreA?.let { text -> item(key = "more-a") { MoreLine(text) } }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(colors.outlineVariant))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            FilesHeader(state, onEvent)
            if (state.filesLoading) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(4) { CamoSkeleton(10.dp, width = (140 + it * 37).dp) }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.fileRows, key = {
                        it.key
                    }) { row -> FileLine(row, row.key == state.focus && focused, onEvent) }
                }
            }
        }
    }
}

@Composable
private fun more(total: Int, shown: Int): String? = if (total >
    shown
) {
    graftText(
        "compare.more",
        "…and {count} more",
        "count" to NumberFormat.getIntegerInstance().format(total - shown),
    )
} else {
    null
}

@Composable
private fun SectionHeader(title: String, label: String, count: Int) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .height(tokens.density.row)
            .background(colors.surfaceContainerLow)
            .padding(horizontal = 12.dp)
            .clearAndSetSemantics {
                heading()
                contentDescription = label
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CamoText(
            // Not upper-cased: branch names stay as git has them (UX Writing).
            title,
            style = tokens.denseStrong,
            color = colors.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        CamoText(NumberFormat.getIntegerInstance().format(count), style = tokens.dense, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun MoreLine(text: String) {
    CamoText(
        text,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        style = Graft.tokens.dense,
        color = Camouflage.colors.onSurfaceVariant,
    )
}

@Composable
private fun CommitLine(
    commit: CompareCommit,
    state: CompareState,
    listFocused: Boolean,
    onEvent: (CompareEvent) -> Unit,
) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val key = "commit:${commit.row.id.hex}"
    val focused = listFocused && state.focus == key
    val age = age(commit.row.authorTime, state.now.takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000))
    val ageText = ageText(age)
    val label =
        graftText(
            "graph.row.commit",
            "Commit {sha}, {subject}, by {author}, {age}",
            "sha" to commit.row.id.short(),
            "subject" to commit.row.subject,
            "author" to commit.author,
            // Read as the graph's rows are: "2 hours ago", not "2 h ago".
            "age" to longAgeText(age),
        )
    Row(
        Modifier
            .fillMaxWidth()
            .height(tokens.density.row)
            .background(if (focused || state.focus == key) colors.surfaceContainerHighest else colors.surface)
            .pointerInput(key) { detectTapGestures(onTap = { onEvent(CompareEvent.Activated(key)) }) }
            .clearAndSetSemantics {
                contentDescription = label
                selected = state.focus == key
            }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CamoIcon(
            graftIcon(GraftIcon.COMMIT),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = colors.onSurfaceVariant,
        )
        CamoText(
            commit.row.id.short(),
            style = tokens.code.copy(fontSize = tokens.dense.fontSize),
            color = colors.onSurfaceVariant,
        )
        CamoText(
            commit.row.subject,
            style = tokens.dense,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        AuthorInitials(commit.author, commit.author)
        CamoText(ageText, style = tokens.dense, color = colors.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun ageText(age: Age): String = when (age) {
    Age.JustNow -> graftText("graph.age.now", "just now")
    is Age.Minutes -> graftText("graph.age.minutes", "{count} min ago", "count" to age.count)
    is Age.Hours -> graftText("graph.age.hours", "{count} h ago", "count" to age.count)
    is Age.Days -> graftText("graph.age.days", "{count} d ago", "count" to age.count)
    is Age.Date -> age.text
}

@Composable
private fun FilesHeader(state: CompareState, onEvent: (CompareEvent) -> Unit) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val density = tokens.density
    val added = state.files.sumOf { it.additions ?: 0 }
    val removed = state.files.sumOf { it.deletions ?: 0 }
    Row(
        Modifier.fillMaxWidth().height(
            tokens.density.row,
        ).background(colors.surfaceContainerLow).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CamoText(
            graftText("details.files.count", "{count} files", "count" to state.files.size),
            style = tokens.denseStrong,
        )
        CamoText("+$added", style = tokens.dense, color = colors.success)
        CamoText("−$removed", style = tokens.dense, color = colors.error)
        Spacer(Modifier.weight(1f))
        listOf(
            Triple(GraftIcon.LIST, graftText("details.files.list", "List"), false),
            Triple(GraftIcon.TREE_VIEW, graftText("details.files.tree", "Tree"), true),
        ).forEach { (icon, label, tree) ->
            CamoIconButton(
                icon = { CamoIcon(graftIcon(icon), contentDescription = null, modifier = Modifier.size(14.dp)) },
                contentDescription = label,
                onClick = { onEvent(CompareEvent.TreeChosen(tree)) },
                modifier = Modifier.size(density.row),
                appearance = Appearance.Ghost,
                checked = state.tree == tree,
                tooltip = label,
            )
        }
    }
}

@Composable
private fun FileLine(row: FileRow, focused: Boolean, onEvent: (CompareEvent) -> Unit) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val label =
        when (row) {
            is FileRow.Folder -> graftText("branches.folder", "Folder {name}", "name" to row.path)
            is FileRow.File -> fileLabel(row.file)
        }
    Row(
        Modifier
            .fillMaxWidth()
            .height(tokens.density.row)
            .background(if (focused) colors.surfaceContainerHighest else colors.surface)
            .pointerInput(row.key) {
                detectTapGestures(onTap = {
                    onEvent(CompareEvent.Focused(row.key))
                }, onDoubleTap = { onEvent(CompareEvent.Activated(row.key)) })
            }.clearAndSetSemantics {
                contentDescription = label
                selected = focused
            }.padding(start = (12 + 14 * row.depth).dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (row) {
            is FileRow.Folder -> {
                CamoIcon(
                    graftIcon(if (row.expanded) GraftIcon.CHEVRON_DOWN else GraftIcon.CHEVRON_RIGHT),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = colors.onSurfaceVariant,
                )
                CamoIcon(
                    graftIcon(GraftIcon.FOLDER),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = colors.onSurfaceVariant,
                )
                CamoText(row.name, style = tokens.dense, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            is FileRow.File -> {
                StatusBadge(row.file.status)
                CamoText(
                    row.label,
                    style = tokens.dense,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (row.file.isBinary) {
                    CamoText(
                        graftText("details.files.binary", "binary"),
                        style = tokens.dense,
                        color = colors.onSurfaceVariant,
                    )
                } else {
                    if ((row.file.additions ?: 0) >
                        0
                    ) {
                        CamoText("+${row.file.additions}", style = tokens.dense, color = colors.success)
                    }
                    if ((row.file.deletions ?: 0) >
                        0
                    ) {
                        CamoText("−${row.file.deletions}", style = tokens.dense, color = colors.error)
                    }
                }
            }
        }
    }
}

/** A picker: the repository's branches and tags, and a typed revision once git resolves it. */
@Composable
private fun Picker(state: CompareState, side: Side, onEvent: (CompareEvent) -> Unit) {
    val local = graftText("branches.local", "Local")
    val remote = graftText("branches.remote", "Remote")
    val tags = graftText("branches.tags", "Tags")
    val commitTitle = graftText("compare.commit", "Commit")
    val groups =
        buildList {
            state.typed?.let { id ->
                add(
                    PaletteGroup(
                        "commit",
                        commitTitle,
                        listOf(PaletteItem(id.hex, id.short(), detail = state.query, icon = GraftIcon.COMMIT)),
                    ),
                )
            }
            for ((title, prefix, icon) in listOf(
                Triple(local, "refs/heads/", GraftIcon.BRANCH),
                Triple(remote, "refs/remotes/", GraftIcon.CLOUD),
                Triple(tags, "refs/tags/", GraftIcon.TAG),
            )) {
                val items =
                    state.refs.filter { it.startsWith(prefix) }.mapNotNull { name ->
                        val short = name.removePrefix(prefix)
                        val m = SpeedSearchMatcher.match(short, state.query) ?: return@mapNotNull null
                        PaletteItem(name, short, icon = icon, matches = m.takeIf { state.query.isNotEmpty() })
                    }.take(50)
                if (items.isNotEmpty()) add(PaletteGroup(prefix, title, items))
            }
        }
    CommandPalette(
        query = state.query,
        onQueryChange = { onEvent(CompareEvent.QueryTyped(it)) },
        groups = groups,
        onSelect = { onEvent(CompareEvent.Picked(side, it.id)) },
        onDismiss = { onEvent(CompareEvent.PickerOpened(null)) },
        placeholder = graftText("compare.pickerHint", "Branch, tag or commit"),
    )
}

@Composable
private fun Note(text: String) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        CamoText(text, style = Graft.tokens.dense, color = Camouflage.colors.onSurfaceVariant)
    }
}

@Composable
private fun Failed(error: Throwable?, onEvent: (CompareEvent) -> Unit) {
    val message = (error as? SinewException)?.displayMessage(Graft.localizer) ?: error?.message.orEmpty()
    val code = (error as? SinewException)?.code
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CamoText(
                if (code != null &&
                    code !in message
                ) {
                    "$message · $code"
                } else {
                    message
                },
                style = Graft.tokens.dense,
                color = Camouflage.colors.error,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val height = Modifier.height(Graft.tokens.density.control)
                CamoButton(graftText("diff.retry", "Retry"), onClick = {
                    onEvent(CompareEvent.Retry)
                }, modifier = height)
                if (error is CommandOutput) {
                    CamoButton(
                        graftText("graph.showOutput", "Show output"),
                        onClick = { onEvent(CompareEvent.OutputShown(true)) },
                        modifier = height,
                        appearance = Appearance.Ghost,
                    )
                }
            }
        }
    }
}

@Composable
private fun OutputDialog(error: Throwable?, onEvent: (CompareEvent) -> Unit) {
    val output = error as? CommandOutput ?: return
    val close = { onEvent(CompareEvent.OutputShown(false)) }
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

@Composable
private fun longAgeText(age: Age): String = when (age) {
    Age.JustNow -> graftText("graph.age.now", "just now")
    is Age.Minutes -> graftText("graph.ageLong.minutes", "{count} minutes ago", "count" to age.count)
    is Age.Hours -> graftText("graph.ageLong.hours", "{count} hours ago", "count" to age.count)
    is Age.Days -> graftText("graph.ageLong.days", "{count} days ago", "count" to age.count)
    is Age.Date -> age.text
}
