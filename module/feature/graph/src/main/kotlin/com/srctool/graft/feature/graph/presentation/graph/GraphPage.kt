// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.graph

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.banner.CamoBanner
import com.srctool.camouflage.core.button.CamoButton
import com.srctool.camouflage.core.checkbox.CamoCheckbox
import com.srctool.camouflage.core.dialog.CamoDialog
import com.srctool.camouflage.core.dialog.CamoDialogFrame
import com.srctool.camouflage.core.icon.CamoIcon
import com.srctool.camouflage.core.iconbutton.CamoIconButton
import com.srctool.camouflage.core.menu.CamoMenuAnchor
import com.srctool.camouflage.core.menu.CamoMenuGroup
import com.srctool.camouflage.core.menu.CamoMenuItem
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.search.CamoSearchField
import com.srctool.camouflage.core.skeleton.CamoSkeleton
import com.srctool.camouflage.core.text.CamoText
import com.srctool.camouflage.core.theme.Appearance
import com.srctool.camouflage.core.theme.Tone
import com.srctool.graft.core.exception.CommandOutput
import com.srctool.graft.core.ui.components.GraftVerticalScrollbar
import com.srctool.graft.core.ui.components.SpeedSearchMode
import com.srctool.graft.core.ui.components.SpeedSearchState
import com.srctool.graft.core.ui.components.TableColumn
import com.srctool.graft.core.ui.components.TableColumnsState
import com.srctool.graft.core.ui.components.TableHeader
import com.srctool.graft.core.ui.components.speedSearch
import com.srctool.graft.core.ui.git.GraphLaneSpacing
import com.srctool.graft.core.ui.git.MAX_DRAWN_LANES
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.core.ui.theme.GraftIcon
import com.srctool.graft.core.ui.theme.graftIcon
import com.srctool.graft.data.app.api.models.GraphAuthorKind
import com.srctool.graft.data.app.api.models.GraphBranches
import com.srctool.graft.feature.graph.presentation.graph.GraphState.Phase
import com.srctool.sinew.exception.displayMessage
import com.srctool.sinew.models.SinewException
import com.srctool.sinew.viewmodel.ObserveEffects
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import java.text.NumberFormat

/** The graph page: [viewModel]'s state, its effects, and [onOpenCommit] for Enter on a commit. */
@Composable
internal fun GraphPage(viewModel: GraphViewModel, onOpenCommit: (String) -> Unit) {
    val state by viewModel.state.collectAsState()

    // LocalClipboard's ClipEntry needs AWT types on desktop; the text clipboard does what Mod+C needs.
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(viewModel) { viewModel.onEvent(GraphEvent.Opened) }
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is GraphEffect.CopyToClipboard -> clipboard.setText(AnnotatedString(effect.text))
            is GraphEffect.OpenCommit -> onOpenCommit(effect.id.hex)
        }
    }
    GraphContent(state, viewModel::onEvent)
}

/** The graph's columns (UI Design, "Center"); the message fills the rest. */
internal fun graphColumns(titles: (String) -> String): List<TableColumn> = listOf(
    TableColumn("refs", titles("refs"), 170.dp, minWidth = 60.dp),
    TableColumn("graph", titles("graph"), 64.dp, minWidth = 32.dp, hideable = false),
    TableColumn("message", titles("message"), 300.dp, fill = true, hideable = false),
    TableColumn("author", titles("author"), 150.dp),
    TableColumn("date", titles("date"), 96.dp),
    TableColumn("sha", titles("sha"), 72.dp),
)

/**
 * The page without its view model: the graph bar, the banners, the table and its states. Screenshot tests draw it
 * from a state directly. [skeletonDelayMs] is how long loading waits before skeleton rows (Screen Inventory: none for
 * under 300 ms).
 */
@Composable
internal fun GraphContent(state: GraphState, onEvent: (GraphEvent) -> Unit, skeletonDelayMs: Long = 300) {
    val colors = Camouflage.colors
    val tableFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val titles =
        mapOf(
            "refs" to graftText("graph.column.refs", "Branch / tag"),
            "graph" to graftText("graph.column.graph", "Graph"),
            "message" to graftText("graph.column.message", "Message"),
            "author" to graftText("graph.column.author", "Author"),
            "date" to graftText("graph.column.date", "Date"),
            "sha" to graftText("graph.column.sha", "SHA"),
        )
    val columns = remember { TableColumnsState(graphColumns { titles.getValue(it) }) }
    LaunchedEffect(state.columns) { columns.restore(state.columns) }
    LaunchedEffect(columns) {
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        snapshotFlow { columns.snapshot() }.debounce(400).collect { onEvent(GraphEvent.ColumnsChanged(it)) }
    }
    // The line from the WIP row to HEAD; the layout doesn't know about it, since the WIP row isn't a commit.
    val wip = remember(state.graph, state.rowCount, state.head, state.stashes, state.wipFiles != null) {
        val graph = state.graph
        if (graph == null || state.wipFiles == null) {
            null
        } else {
            wipLine(graph, state.rowCount, state.head, state.stashes.mapTo(HashSet()) { it.baseRow })
        }
    }
    // The graph column is at least as wide as the widest row so far, the WIP line's lane included.
    val lanes = maxOf(state.widest, wip?.let { it.lane + 1 } ?: 0)
    val lanesWidth = GraphLaneSpacing * minOf(lanes, MAX_DRAWN_LANES) + 8.dp
    LaunchedEffect(lanesWidth) {
        val now = columns.width("graph")
        if (now < lanesWidth) columns.resizeBy("graph", lanesWidth - now)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.surface)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.F && event.isMod() && !event.isAltPressed) {
                    searchFocus.requestFocus()
                    true
                } else {
                    false
                }
            },
    ) {
        GraphBar(state, onEvent, searchFocus, tableFocus)
        Banners(state, onEvent)
        TableHeader(columns)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.phase == Phase.Empty -> Centered(graftText("graph.empty", "This repository has no commits yet."))

                state.filteredToNothing ->
                    Centered(graftText("graph.filtered", "No commits on the selected branches.")) {
                        CamoButton(
                            graftText("graph.filtered.action", "Show all branches"),
                            onClick = { onEvent(GraphEvent.ShowAllBranches) },
                            modifier = Modifier.height(Graft.tokens.density.formControl),
                        )
                    }

                state.phase == Phase.Failed && state.rowCount == 0 -> Centered { ErrorBody(state, onEvent) }

                state.rowCount == 0 && state.wipFiles == null -> Skeleton(state.phase, skeletonDelayMs)

                else -> GraphTable(state, wip, columns, onEvent, tableFocus)
            }
        }
        StatusLine(state)
    }
    if (state.outputShown) OutputDialog(state.error, onEvent)
}

private fun KeyEvent.isMod(): Boolean = isMetaPressed || isCtrlPressed

@Composable
private fun GraphBar(
    state: GraphState,
    onEvent: (GraphEvent) -> Unit,
    searchFocus: FocusRequester,
    tableFocus: FocusRequester,
) {
    val density = Graft.tokens.density
    val all = graftText("graph.branches.all", "All branches")
    val current = graftText("graph.branches.current", "Current branch")
    val branchLabel =
        when {
            state.soloCount > 0 -> graftText("graph.branches.selected", "{count} selected", "count" to state.soloCount)
            state.view.branches == GraphBranches.CURRENT -> current
            else -> all
        }
    val kinds = state.view.authorKinds
    val authorsLabel =
        if (kinds.size == GraphAuthorKind.entries.size) {
            graftText("graph.authors.all", "Authors: all")
        } else {
            graftText("graph.authors.some", "Authors: {count} of 3", "count" to kinds.size)
        }
    val kindNames =
        mapOf(
            GraphAuthorKind.PERSON to graftText("graph.author.person", "People"),
            GraphAuthorKind.AI_ASSISTED to graftText("graph.author.ai", "AI-assisted"),
            GraphAuthorKind.BOT to graftText("graph.author.bot", "Bots"),
        )
    Row(
        Modifier
            .fillMaxWidth()
            .height(density.control + 12.dp)
            .background(Camouflage.colors.surfaceContainerLow)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val branchItems =
            listOf(
                CamoMenuGroup(
                    listOf(
                        branchItem(all, GraphBranches.ALL, state, onEvent),
                        branchItem(current, GraphBranches.CURRENT, state, onEvent),
                    ),
                    exclusive = true,
                ),
            )
        CamoMenuAnchor(items = branchItems) { open -> BarMenuButton(branchLabel, open) }
        CamoCheckbox(
            state.view.hideRemotes,
            onCheckedChange = { onEvent(GraphEvent.HideRemotesToggled) },
            label = graftText("graph.hideRemotes", "Hide remotes"),
            modifier = Modifier.height(density.control),
        )
        CamoCheckbox(
            state.view.firstParent,
            onCheckedChange = { onEvent(GraphEvent.FirstParentToggled) },
            label = graftText("graph.firstParent", "First parent"),
            modifier = Modifier.height(density.control),
        )
        val authorItems =
            GraphAuthorKind.entries.map { kind ->
                CamoMenuItem(
                    kindNames.getValue(kind),
                    onClick = { onEvent(GraphEvent.AuthorKindToggled(kind)) },
                    checked = kind in kinds,
                )
            }
        CamoMenuAnchor(items = authorItems) { open -> BarMenuButton(authorsLabel, open) }
        Spacer(Modifier.weight(1f))
        SearchBox(state, onEvent, searchFocus, tableFocus)
    }
}

private fun branchItem(label: String, branches: GraphBranches, state: GraphState, onEvent: (GraphEvent) -> Unit) =
    CamoMenuItem(
        label,
        onClick = { onEvent(GraphEvent.BranchesChosen(branches)) },
        checked = state.soloCount == 0 && state.view.branches == branches,
    )

@Composable
private fun BarMenuButton(label: String, open: () -> Unit) {
    CamoButton(
        label,
        onClick = open,
        modifier = Modifier.height(Graft.tokens.density.control),
        appearance = Appearance.Ghost,
        suffixIcon = {
            CamoIcon(graftIcon(GraftIcon.CHEVRON_DOWN), contentDescription = null, modifier = Modifier.size(12.dp))
        },
    )
}

@Composable
private fun SearchBox(
    state: GraphState,
    onEvent: (GraphEvent) -> Unit,
    searchFocus: FocusRequester,
    tableFocus: FocusRequester,
) {
    val search = state.search
    val density = Graft.tokens.density
    val count =
        when {
            !search.active -> ""

            search.matchRows.isEmpty() && search.running -> graftText("graph.search.searching", "Searching…")

            search.matchRows.isEmpty() -> graftText("graph.search.none", "No matches")

            search.current >= 0 ->
                graftText(
                    "graph.search.count",
                    "{current} of {total}",
                    "current" to search.current + 1,
                    "total" to search.matchRows.size,
                )

            else -> graftText("graph.search.total", "{total} matches", "total" to search.matchRows.size)
        }
    val kindNames =
        mapOf(
            SearchKind.MESSAGE to graftText("graph.search.message", "Message"),
            SearchKind.AUTHOR to graftText("graph.search.author", "Author"),
            SearchKind.PATH to graftText("graph.search.path", "Path"),
            SearchKind.CONTENT to graftText("graph.search.content", "Content"),
        )
    CamoMenuAnchor(
        items =
        listOf(
            CamoMenuGroup(
                SearchKind.entries.map { k ->
                    CamoMenuItem(
                        kindNames.getValue(k),
                        onClick = { onEvent(GraphEvent.SearchKindChosen(k)) },
                        checked = k == search.kind,
                    )
                },
                exclusive = true,
            ),
        ),
    ) { open ->
        CamoIconButton(
            icon = {
                CamoIcon(graftIcon(GraftIcon.FILTER), contentDescription = null, modifier = Modifier.size(density.icon))
            },
            contentDescription = graftText("graph.search.in", "Search in") + ": " + kindNames.getValue(search.kind),
            onClick = open,
            modifier = Modifier.size(density.control),
            appearance = Appearance.Ghost,
        )
    }
    CamoSearchField(
        query = search.query,
        onQueryChange = { onEvent(GraphEvent.SearchTyped(it)) },
        onSearch = { onEvent(GraphEvent.NextMatch) },
        placeholder = graftText("graph.search.placeholder", "Search message, hash…"),
        modifier =
        Modifier
            .width(220.dp)
            .height(density.control)
            .focusRequester(searchFocus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.Enter && event.isShiftPressed -> onEvent(GraphEvent.PreviousMatch).let { true }

                    event.key == Key.Escape -> {
                        onEvent(GraphEvent.SearchCleared)
                        tableFocus.requestFocus()
                        true
                    }

                    else -> false
                }
            },
    )
    val error = search.error != null || (search.active && !search.running && search.matchRows.isEmpty())
    CamoText(
        count,
        modifier = Modifier.width(80.dp).semantics { liveRegion = LiveRegionMode.Polite },
        style = Graft.tokens.dense,
        color = if (error) Camouflage.colors.error else Camouflage.colors.onSurfaceVariant,
        maxLines = 1,
    )
    val next = graftText("graph.search.next", "Next match")
    MatchButton(GraftIcon.CHEVRON_DOWN, next, search.matchRows.isNotEmpty()) { onEvent(GraphEvent.NextMatch) }
}

@Composable
private fun MatchButton(icon: GraftIcon, label: String, enabled: Boolean, onClick: () -> Unit) {
    val density = Graft.tokens.density
    CamoIconButton(
        icon = { CamoIcon(graftIcon(icon), contentDescription = null, modifier = Modifier.size(density.icon)) },
        contentDescription = label,
        onClick = { if (enabled) onClick() },
        modifier = Modifier.size(density.control),
        appearance = Appearance.Ghost,
        tooltip = label,
    )
}

@Composable
private fun Banners(state: GraphState, onEvent: (GraphEvent) -> Unit) {
    val density = Graft.tokens.density
    when (state.commitGraph) {
        CommitGraphOffer.Hidden -> Unit

        CommitGraphOffer.Shown, CommitGraphOffer.Writing -> {
            val writing = state.commitGraph == CommitGraphOffer.Writing
            CamoBanner(
                if (writing) {
                    graftText("graph.commitGraph.writing", "Writing the commit-graph file…")
                } else {
                    graftText("graph.commitGraph.message", COMMIT_GRAPH_OFFER)
                },
                modifier = Modifier.fillMaxWidth(),
                tone = Tone.Info,
                actions = {
                    CamoButton(
                        graftText("graph.commitGraph.accept", "Speed up the graph"),
                        onClick = { onEvent(GraphEvent.SpeedUpAccepted) },
                        modifier = Modifier.height(density.control),
                        enabled = !writing,
                        loading = writing,
                    )
                    if (!writing) {
                        CamoButton(
                            graftText("graph.commitGraph.decline", "Not now"),
                            onClick = { onEvent(GraphEvent.SpeedUpDeclined) },
                            modifier = Modifier.height(density.control),
                            appearance = Appearance.Ghost,
                        )
                    }
                },
            )
        }
    }
    // A failure after some rows keeps them, with the error above.
    if (state.error != null && (state.phase != Phase.Failed || state.rowCount > 0)) {
        CamoBanner(
            errorText(state.error),
            modifier = Modifier.fillMaxWidth(),
            tone = Tone.Error,
            actions = { ErrorActions(state, onEvent) },
        )
    }
}

private const val COMMIT_GRAPH_OFFER =
    "Loading the graph is slow here: this repository has no commit-graph file. Git can write one to speed it up."

@Composable
private fun errorText(error: Throwable): String {
    val message = (error as? SinewException)?.displayMessage(Graft.localizer) ?: error.message.orEmpty()
    val code = (error as? SinewException)?.code
    // Sinew's message already names the code; one without it gets it appended (UX Writing: codes always shown).
    return if (code != null && code !in message) "$message · $code" else message
}

@Composable
private fun ErrorBody(state: GraphState, onEvent: (GraphEvent) -> Unit) {
    val error = state.error ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CamoText(errorText(error), style = Graft.tokens.dense, color = Camouflage.colors.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ErrorActions(state, onEvent) }
    }
}

@Composable
private fun ErrorActions(state: GraphState, onEvent: (GraphEvent) -> Unit) {
    val height = Graft.tokens.density.control
    CamoButton(
        graftText("graph.retry", "Retry"),
        onClick = { onEvent(GraphEvent.Retry) },
        modifier = Modifier.height(height),
    )
    if (state.error is CommandOutput) {
        CamoButton(
            graftText("graph.showOutput", "Show output"),
            onClick = { onEvent(GraphEvent.OutputShown(true)) },
            modifier = Modifier.height(height),
            appearance = Appearance.Ghost,
        )
    }
}

@Composable
private fun OutputDialog(error: Throwable?, onEvent: (GraphEvent) -> Unit) {
    val output = error as? CommandOutput ?: return
    val close = { onEvent(GraphEvent.OutputShown(false)) }
    CamoDialogFrame(onDismissRequest = close) {
        CamoDialog(
            message = output.argv.joinToString(" "),
            title = graftText("graph.output.title", "Git output"),
            confirm = { CamoButton(graftText("graph.output.close", "Close"), onClick = close) },
        ) {
            output.exitCode?.let {
                CamoText(graftText("graph.output.exit", "Exit code {code}", "code" to it), style = Graft.tokens.dense)
            }
            Box(
                Modifier
                    .height(200.dp)
                    .fillMaxWidth()
                    .background(Camouflage.colors.surfaceContainerLow)
                    .verticalScroll(rememberScrollState()),
            ) {
                val style = Graft.tokens.code.copy(color = Camouflage.colors.onSurface)
                BasicText(output.stderr, Modifier.padding(8.dp), style = style)
            }
        }
    }
}

@Composable
private fun Centered(text: String? = null, action: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (text != null) CamoText(text, style = Graft.tokens.dense, color = Camouflage.colors.onSurfaceVariant)
            action?.invoke()
        }
    }
}

@Composable
private fun Skeleton(phase: Phase, delayMs: Long) {
    var shown by remember { mutableStateOf(delayMs == 0L) }
    LaunchedEffect(phase) {
        if (!shown && phase == Phase.Loading) {
            delay(delayMs)
            shown = true
        }
    }
    if (!shown) return
    val row = Graft.tokens.density.graphRow
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        repeat(12) { i ->
            Row(
                Modifier.height(row),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CamoSkeleton(10.dp, width = 90.dp)
                CamoSkeleton(10.dp, width = 10.dp)
                CamoSkeleton(10.dp, width = (180 + (i * 37) % 160).dp)
                CamoSkeleton(10.dp, width = 80.dp)
            }
        }
    }
}

@Composable
private fun StatusLine(state: GraphState) {
    val count = NumberFormat.getIntegerInstance().format(state.rowCount)
    val text =
        when (state.phase) {
            Phase.Loading -> graftText("graph.loading", "Loading history… {count} commits", "count" to count)
            Phase.Loaded, Phase.Failed -> graftText("graph.count", "{count} commits", "count" to count)
            Phase.Idle, Phase.Empty -> ""
        }
    Row(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .background(Camouflage.colors.surfaceContainerLow)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CamoText(text, style = Graft.tokens.dense, color = Camouflage.colors.onSurfaceVariant, maxLines = 1)
    }
}

/** The table: the WIP row, then a row per commit; the keyboard path of Accessibility, "Graph". */
@Composable
private fun GraphTable(
    state: GraphState,
    wip: WipLine?,
    columns: TableColumnsState,
    onEvent: (GraphEvent) -> Unit,
    focus: FocusRequester,
) {
    val list = rememberLazyListState()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusColor = Camouflage.colors.primary
    val speed = remember { SpeedSearchState(SpeedSearchMode.JUMP) }
    val count = NumberFormat.getIntegerInstance().format(state.rowCount)
    val label = graftText("graph.list", "Commit graph, {count} commits", "count" to count)

    // Typing jumps to the next row whose message or ref matches (speed search, JUMP mode).
    LaunchedEffect(speed.query) {
        if (speed.query.isEmpty()) return@LaunchedEffect
        val graph = state.graph ?: return@LaunchedEffect
        val from = (state.selection as? Selection.Row)?.index ?: 0
        val hit =
            (from until state.rowCount).firstOrNull { i ->
                val row = graph[i]
                speed.matches(row.commit.subject) != null ||
                    state.refs[row.commit.id].orEmpty().any { speed.matches(it.name) != null }
            }
        if (hit != null) onEvent(GraphEvent.Selected(Selection.Row(hit)))
    }
    // The selection stays in view.
    val table = state.table
    LaunchedEffect(state.selection, table.size) {
        val index = table.indexOf(state.selection) ?: return@LaunchedEffect
        val visible = list.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: 0
        val last = visible.lastOrNull()?.index ?: 0
        if (index <= first || index >= last) list.scrollToItem(maxOf(index - 2, 0))
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .focusRequester(focus)
                .speedSearch(speed)
                .onPreviewKeyEvent { event -> tableKey(event, list, onEvent) }
                .focusable(interactionSource = interaction)
                .semantics { contentDescription = label }
                .drawWithContent {
                    drawContent()
                    if (focused) {
                        val w = 2.dp.toPx()
                        val inset = Size(size.width - w, size.height - w)
                        drawRect(focusColor, Offset(w / 2, w / 2), inset, style = Stroke(w))
                    }
                },
            state = list,
        ) {
            val graph = state.graph
            // The WIP row, the commits, and each stash just above its base commit (TableLayout).
            items(
                table.size,
                key = { t ->
                    when (val e = table.entry(t)) {
                        TableEntry.Wip -> "wip"
                        is TableEntry.Stash -> "stash-" + e.placement.entry.id.hex
                        is TableEntry.Commit -> graph?.get(e.row)?.commit?.id?.hex ?: "row-${e.row}"
                    }
                },
            ) { t ->
                when (val e = table.entry(t)) {
                    TableEntry.Wip -> WipRow(state, wip, columns) { onEvent(GraphEvent.Selected(Selection.Wip)) }

                    is TableEntry.Stash -> {
                        val select = { onEvent(GraphEvent.Selected(Selection.Stash(e.placement.entry.id))) }
                        StashRow(state, graph ?: return@items, e.placement, wip, columns, onSelect = select) {
                            select()
                            onEvent(GraphEvent.OpenSelected)
                        }
                    }

                    is TableEntry.Commit -> {
                        if (graph == null) return@items
                        val select = { onEvent(GraphEvent.Selected(Selection.Row(e.row))) }
                        CommitRow(
                            state,
                            graph,
                            e.row,
                            wip,
                            columns,
                            onSelect = select,
                            onOpen = {
                                select()
                                onEvent(GraphEvent.OpenSelected)
                            },
                            onMark = { onEvent(GraphEvent.Marked(Selection.Row(e.row))) },
                        )
                    }
                }
            }
        }
        GraftVerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

private fun tableKey(event: KeyEvent, list: LazyListState, onEvent: (GraphEvent) -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val page = maxOf(list.layoutInfo.visibleItemsInfo.size - 1, 1)
    val alt = event.isAltPressed
    val mod = event.isMod()
    val handled: GraphEvent =
        when {
            alt && event.key == Key.DirectionDown -> GraphEvent.FirstParent
            alt && event.key == Key.DirectionUp -> GraphEvent.FirstChild
            alt && event.key == Key.DirectionRight -> GraphEvent.OtherParent
            mod && alt && event.key == Key.MoveHome -> GraphEvent.JumpToHead
            mod && event.key == Key.C -> GraphEvent.CopyHash
            mod && event.key == Key.Spacebar -> GraphEvent.MarkSelected
            mod && event.key == Key.D -> GraphEvent.CompareMarked
            alt || mod -> return false
            event.key == Key.DirectionDown -> GraphEvent.MovedBy(1)
            event.key == Key.DirectionUp -> GraphEvent.MovedBy(-1)
            event.key == Key.PageDown -> GraphEvent.MovedBy(page)
            event.key == Key.PageUp -> GraphEvent.MovedBy(-page)
            event.key == Key.MoveHome -> GraphEvent.MovedBy(Int.MIN_VALUE / 2)
            event.key == Key.MoveEnd -> GraphEvent.MovedBy(Int.MAX_VALUE / 2)
            event.key == Key.LeftBracket -> GraphEvent.NextRef(-1)
            event.key == Key.RightBracket -> GraphEvent.NextRef(+1)
            event.key == Key.Enter -> GraphEvent.OpenSelected
            else -> return false
        }
    onEvent(handled)
    return true
}
