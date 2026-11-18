// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.branches

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.button.CamoButton
import com.srctool.camouflage.core.icon.CamoIcon
import com.srctool.camouflage.core.iconbutton.CamoIconButton
import com.srctool.camouflage.core.menu.CamoMenuAnchor
import com.srctool.camouflage.core.menu.CamoMenuController
import com.srctool.camouflage.core.menu.CamoMenuItem
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.skeleton.CamoSkeleton
import com.srctool.camouflage.core.text.CamoText
import com.srctool.camouflage.core.theme.Appearance
import com.srctool.graft.core.ui.components.SpeedSearchField
import com.srctool.graft.core.ui.components.SpeedSearchState
import com.srctool.graft.core.ui.components.highlightMatches
import com.srctool.graft.core.ui.components.speedSearch
import com.srctool.graft.core.ui.git.aheadBehind
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.core.ui.theme.GraftIcon
import com.srctool.graft.core.ui.theme.graftIcon
import com.srctool.graft.data.git.api.models.RefKind
import com.srctool.sinew.exception.displayMessage
import com.srctool.sinew.models.SinewException
import com.srctool.sinew.viewmodel.ObserveEffects

/** The left panel page: [viewModel]'s state and its clipboard effect. */
@Composable
internal fun BranchesPage(viewModel: BranchesViewModel) {
    val state by viewModel.state.collectAsState()

    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(viewModel) { viewModel.onEvent(BranchesEvent.Opened) }
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is BranchesEffect.CopyToClipboard -> clipboard.setText(AnnotatedString(effect.text))
        }
    }
    BranchesContent(state, viewModel::onEvent)
}

/** The panel without its view model, for screenshot tests. */
@Composable
internal fun BranchesContent(state: BranchesState, onEvent: (BranchesEvent) -> Unit) {
    val colors = Camouflage.colors
    val speed = remember { SpeedSearchState() }
    LaunchedEffect(speed.query) { onEvent(BranchesEvent.Searched(speed.query)) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val list = rememberLazyListState()
    val title = graftText("branches.title", "Branches")
    LaunchedEffect(state.focus) {
        val index = state.focusIndex
        if (index < 0) return@LaunchedEffect
        val visible = list.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == index }) list.scrollToItem(maxOf(index - 2, 0))
    }
    Column(Modifier.fillMaxSize().background(colors.surfaceContainerLowest)) {
        when (state.phase) {
            BranchesState.Phase.Loading -> LoadingRows()

            BranchesState.Phase.Failed -> FailedBody(state, onEvent)

            BranchesState.Phase.Ready -> {
                SpeedSearchField(
                    speed,
                    matchCount = state.rows.count {
                        it is PanelRow.RefItem ||
                            it is PanelRow.StashItem
                    },
                )
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .speedSearch(speed)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val handled: BranchesEvent =
                                when {
                                    event.key == Key.DirectionDown -> BranchesEvent.Moved(1)

                                    event.key == Key.DirectionUp -> BranchesEvent.Moved(-1)

                                    event.key == Key.DirectionRight -> BranchesEvent.Expand

                                    event.key == Key.DirectionLeft -> BranchesEvent.Collapse

                                    event.key == Key.Enter -> BranchesEvent.Activated(
                                        state.focus ?: return@onPreviewKeyEvent false,
                                    )

                                    (event.key == Key.F10 && event.isShiftPressed) || event.key == Key.Menu ->
                                        BranchesEvent.MenuShown(state.focus)

                                    else -> return@onPreviewKeyEvent false
                                }
                            onEvent(handled)
                            true
                        }.focusable(interactionSource = interaction)
                        .semantics { contentDescription = title }
                        .drawWithContent {
                            drawContent()
                            if (focused) {
                                val w = 2.dp.toPx()
                                drawRect(
                                    colors.primary,
                                    Offset(w / 2, w / 2),
                                    Size(size.width - w, size.height - w),
                                    style = Stroke(w),
                                )
                            }
                        },
                    state = list,
                ) {
                    items(state.rows, key = { it.key }) { row ->
                        PanelRowView(row, row.key == state.focus && focused, state.menuFor == row.key, onEvent)
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingRows() {
    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(8) { i -> CamoSkeleton(10.dp, width = (60 + (i * 29) % 90).dp) }
    }
}

@Composable
private fun FailedBody(state: BranchesState, onEvent: (BranchesEvent) -> Unit) {
    val error = state.error ?: return
    val message = (error as? SinewException)?.displayMessage(Graft.localizer) ?: error.message.orEmpty()
    val code = (error as? SinewException)?.code
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CamoText(
            if (code != null && code !in message) "$message · $code" else message,
            style = Graft.tokens.dense,
            color = Camouflage.colors.error,
        )
        CamoButton(
            graftText("branches.retry", "Retry"),
            onClick = { onEvent(BranchesEvent.Retry) },
            modifier = Modifier.height(Graft.tokens.density.control),
        )
    }
}

@Composable
private fun PanelRowView(row: PanelRow, focused: Boolean, menuOpen: Boolean, onEvent: (BranchesEvent) -> Unit) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val description = rowDescription(row)
    val base =
        Modifier
            .fillMaxWidth()
            .height(tokens.density.row)
            .background(if (focused) colors.surfaceContainerHighest else colors.surfaceContainerLowest)
            .hoverable(hover)
            .pointerInput(row.key) {
                detectTapGestures(onTap = {
                    onEvent(BranchesEvent.Focused(row.key))
                    onEvent(BranchesEvent.Activated(row.key))
                })
            }.padding(start = (8 + 12 * row.depth).dp, end = 4.dp)
    when (row) {
        is PanelRow.Header ->
            Row(
                base.clearAndSetSemantics {
                    contentDescription = description
                    selected = focused
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Chevron(open = !row.collapsed)
                CamoText(
                    sectionName(row.section).uppercase(),
                    style = tokens.denseStrong,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                CamoText(row.count.toString(), style = tokens.dense, color = colors.onSurfaceVariant)
            }

        is PanelRow.Folder ->
            Row(
                base.clearAndSetSemantics {
                    contentDescription = description
                    selected = focused
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Chevron(open = row.expanded)
                RowIcon(if (row.remote) GraftIcon.CLOUD else GraftIcon.FOLDER)
                CamoText(row.name, style = tokens.dense, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

        is PanelRow.RefItem -> {
            val ref = row.ref
            val showActions = focused || hovered || menuOpen
            val menu = remember { CamoMenuController() }
            LaunchedEffect(menuOpen) { if (menuOpen) menu.open() else menu.close() }
            // Closed by a click outside or Esc: the panel forgets it was open.
            LaunchedEffect(menu.isOpen) { if (!menu.isOpen && menuOpen) onEvent(BranchesEvent.MenuShown(null)) }
            Row(
                base.clearAndSetSemantics {
                    contentDescription = description
                    selected = focused
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    CamoMenuAnchor(items = refMenu(row, onEvent), controller = menu) { _ ->
                        Row(
                            Modifier.alpha(if (row.hidden) 0.5f else 1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(Modifier.width(12.dp))
                            if (row.pinned) RowIcon(GraftIcon.PIN)
                            if (ref.isHead) RowIcon(GraftIcon.CHECKMARK)
                            RowIcon(
                                when (ref.kind) {
                                    RefKind.TAG -> GraftIcon.TAG
                                    else -> GraftIcon.BRANCH
                                },
                            )
                            BasicText(
                                highlightMatches(row.label, row.matches),
                                style = (if (ref.isHead) tokens.denseStrong else tokens.dense).copy(
                                    color = colors.onSurface,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            val counts = aheadBehind(ref.ahead ?: 0, ref.behind ?: 0)
                            if (counts.isNotEmpty()) {
                                CamoText(
                                    "  $counts",
                                    style = tokens.dense,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                            if (row.solo) {
                                Box(
                                    Modifier.padding(
                                        start = 6.dp,
                                    ).size(
                                        6.dp,
                                    ).background(colors.primary, androidx.compose.foundation.shape.CircleShape),
                                )
                            }
                            if (row.hidden) RowIcon(GraftIcon.VIEW_OFF, Modifier.padding(start = 4.dp))
                        }
                    }
                }
                if (showActions) {
                    val name = ref.shortName
                    ToggleButton(
                        if (row.hidden) GraftIcon.VIEW else GraftIcon.VIEW_OFF,
                        graftText(
                            "branches.hide",
                            "Hide {name}",
                            "name" to name,
                        ),
                        row.hidden,
                    ) {
                        onEvent(BranchesEvent.HideToggled(ref.name))
                    }
                    ToggleButton(
                        GraftIcon.FILTER,
                        graftText("branches.solo", "Show only {name}", "name" to name),
                        row.solo,
                    ) {
                        onEvent(BranchesEvent.SoloToggled(ref.name))
                    }
                    ToggleButton(GraftIcon.PIN, graftText("branches.pin", "Pin {name}", "name" to name), row.pinned) {
                        onEvent(BranchesEvent.PinToggled(ref.name))
                    }
                }
            }
        }

        is PanelRow.StashItem ->
            Row(
                base.clearAndSetSemantics {
                    contentDescription = description
                    selected = focused
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(12.dp))
                RowIcon(GraftIcon.STASH)
                CamoText(
                    "${row.entry.index}",
                    style = tokens.code.copy(fontSize = tokens.dense.fontSize),
                    color = colors.onSurfaceVariant,
                )
                BasicText(
                    highlightMatches(row.entry.message, row.matches),
                    style = tokens.dense.copy(color = colors.onSurface),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false),
                )
                row.entry.branch?.let {
                    CamoText("  ($it)", style = tokens.dense, color = colors.onSurfaceVariant, maxLines = 1)
                }
            }

        is PanelRow.Empty ->
            Row(
                base.clearAndSetSemantics {
                    contentDescription = description
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(12.dp))
                CamoText(description, style = tokens.dense, color = colors.onSurfaceVariant)
            }
    }
}

@Composable
private fun refMenu(row: PanelRow.RefItem, onEvent: (BranchesEvent) -> Unit): List<CamoMenuItem> {
    val close = { onEvent(BranchesEvent.MenuShown(null)) }
    val name = row.ref.name
    return listOf(
        CamoMenuItem(graftText("branches.menu.hide", "Hide"), onClick = {
            onEvent(BranchesEvent.HideToggled(name))
            close()
        }, checked = row.hidden),
        CamoMenuItem(graftText("branches.menu.solo", "Show only this"), onClick = {
            onEvent(BranchesEvent.SoloToggled(name))
            close()
        }, checked = row.solo),
        CamoMenuItem(graftText("branches.menu.pin", "Pin"), onClick = {
            onEvent(BranchesEvent.PinToggled(name))
            close()
        }, checked = row.pinned),
        CamoMenuItem(graftText("branches.menu.copy", "Copy name"), onClick = {
            onEvent(BranchesEvent.CopyName(row.ref.shortName))
            close()
        }),
    )
}

@Composable
private fun Chevron(open: Boolean) {
    CamoIcon(
        graftIcon(if (open) GraftIcon.CHEVRON_DOWN else GraftIcon.CHEVRON_RIGHT),
        contentDescription = null,
        modifier = Modifier.padding(end = 4.dp).size(12.dp),
        tint = Camouflage.colors.onSurfaceVariant,
    )
}

@Composable
private fun RowIcon(icon: GraftIcon, modifier: Modifier = Modifier) {
    CamoIcon(
        graftIcon(icon),
        contentDescription = null,
        modifier = modifier.padding(end = 4.dp).size(14.dp),
        tint = Camouflage.colors.onSurfaceVariant,
    )
}

@Composable
private fun ToggleButton(icon: GraftIcon, label: String, on: Boolean, onClick: () -> Unit) {
    CamoIconButton(
        icon = { CamoIcon(graftIcon(icon), contentDescription = null, modifier = Modifier.size(14.dp)) },
        contentDescription = label,
        onClick = onClick,
        modifier = Modifier.size(Graft.tokens.density.row - 2.dp),
        appearance = Appearance.Ghost,
        checked = on,
        tooltip = label,
    )
}

@Composable
private fun sectionName(section: Section): String = when (section) {
    Section.LOCAL -> graftText("branches.local", "Local")
    Section.REMOTE -> graftText("branches.remote", "Remote")
    Section.TAGS -> graftText("branches.tags", "Tags")
    Section.STASHES -> graftText("branches.stashes", "Stashes")
}

/** The row's one label (Task 14 wireframe): "Branch main, checked out, 1 ahead, pinned". */
@Composable
internal fun rowDescription(row: PanelRow): String = when (row) {
    is PanelRow.Header ->
        graftText(
            "branches.header",
            "{section}, {count}",
            "section" to sectionName(row.section),
            "count" to row.count,
        ) +
            if (row.collapsed) ", " + graftText("branches.collapsed", "collapsed") else ""

    is PanelRow.Folder ->
        graftText("branches.folder", "Folder {name}", "name" to row.path) +
            if (row.expanded) "" else ", " + graftText("branches.collapsed", "collapsed")

    is PanelRow.RefItem -> {
        val ref = row.ref
        val parts =
            mutableListOf(
                when (ref.kind) {
                    RefKind.TAG -> graftText("ui.ref.tag", "Tag {name}", "name" to ref.shortName)
                    RefKind.REMOTE_BRANCH -> graftText("ui.ref.remote", "Remote branch {name}", "name" to ref.shortName)
                    else -> graftText("ui.ref.branch", "Branch {name}", "name" to ref.shortName)
                },
            )
        if (ref.isHead) parts += graftText("ui.ref.head", "checked out")
        if ((ref.ahead ?: 0) > 0) parts += graftText("ui.ref.ahead", "{count} ahead", "count" to ref.ahead!!)
        if ((ref.behind ?: 0) > 0) parts += graftText("ui.ref.behind", "{count} behind", "count" to ref.behind!!)
        if (row.pinned) parts += graftText("branches.pinned", "pinned")
        if (row.hidden) parts += graftText("branches.hidden", "hidden")
        if (row.solo) parts += graftText("branches.soloed", "shown alone")
        parts.joinToString(", ")
    }

    is PanelRow.StashItem ->
        listOfNotNull(
            graftText(
                "graph.stash.label",
                "Stash {index}, {message}",
                "index" to row.entry.index,
                "message" to row.entry.message,
            ),
            row.entry.branch?.let { graftText("graph.stash.branch", "made on {branch}", "branch" to it) },
        ).joinToString(", ")

    is PanelRow.Empty ->
        when (row.section) {
            Section.LOCAL -> graftText("branches.empty.local", "No branches.")
            Section.REMOTE -> graftText("branches.empty.remote", "No remote branches.")
            Section.TAGS -> graftText("branches.empty.tags", "No tags.")
            Section.STASHES -> graftText("branches.empty.stashes", "No stashes.")
        }
}
