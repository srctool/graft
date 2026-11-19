// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.presentation.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.banner.CamoBanner
import com.srctool.camouflage.core.button.CamoButton
import com.srctool.camouflage.core.checkbox.CamoCheckbox
import com.srctool.camouflage.core.dialog.CamoDialog
import com.srctool.camouflage.core.dialog.CamoDialogFrame
import com.srctool.camouflage.core.icon.CamoIcon
import com.srctool.camouflage.core.iconbutton.CamoIconButton
import com.srctool.camouflage.core.menu.CamoMenuAnchor
import com.srctool.camouflage.core.menu.CamoMenuItem
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.search.CamoSearchField
import com.srctool.camouflage.core.skeleton.CamoSkeleton
import com.srctool.camouflage.core.text.CamoText
import com.srctool.camouflage.core.textfield.CamoTextField
import com.srctool.camouflage.core.theme.Appearance
import com.srctool.camouflage.core.theme.Tone
import com.srctool.graft.core.exception.CommandOutput
import com.srctool.graft.core.ui.components.LocalFolderPicker
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.core.ui.theme.GraftIcon
import com.srctool.graft.core.ui.theme.graftIcon
import com.srctool.graft.data.git.api.models.Head
import com.srctool.graft.feature.home.presentation.clone.CloneDialog
import com.srctool.graft.feature.home.presentation.initrepo.InitDialog
import com.srctool.graft.feature.home.presentation.onboarding.OnboardingDialog
import com.srctool.graft.feature.home.presentation.rememberDestinationOwner
import com.srctool.graft.feature.home.search.HomeCommand
import com.srctool.sinew.exception.displayMessage
import com.srctool.sinew.models.SinewException
import com.srctool.sinew.viewmodel.ObserveEffects
import org.koin.compose.viewmodel.koinViewModel
import java.awt.datatransfer.DataFlavor
import java.nio.file.Path

/**
 * Home (S1): [viewModel]'s state, and what it opens handed to the host: [onOpen] a repository's root, [onOpenAll] each of
 * a workspace's. A folder dropped on the window opens like one chosen with **Open…**.
 */
@Composable
internal fun HomePage(
    viewModel: HomeViewModel,
    onOpen: (Path) -> Unit,
    onOpenAll: (List<Path>) -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onSearch: () -> Unit,
    welcomeRequested: Boolean,
    onWelcomeHandled: () -> Unit,
    command: HomeCommand?,
    onCommandHandled: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) { viewModel.onEvent(HomeEvent.Opened) }
    // The host asks for the welcome steps again (Settings > Onboarding); it's told once they're on screen.
    // An action chosen elsewhere (Search Everywhere) is carried out here, where the pickers and dialogs are.
    val picker = LocalFolderPicker.current
    val openTitle = graftText("home.open.title", "Choose a repository's folder")
    val scanTitle = graftText("home.scan.pick", "Choose a folder to scan for repositories")
    LaunchedEffect(command) {
        when (command) {
            HomeCommand.Open -> picker.pick(openTitle, null)?.let { viewModel.onEvent(HomeEvent.OpenFolder(it)) }
            HomeCommand.Clone -> viewModel.onEvent(HomeEvent.DialogSet(HomeDialog.Clone()))
            HomeCommand.Init -> viewModel.onEvent(HomeEvent.DialogSet(HomeDialog.Init()))
            HomeCommand.Scan -> picker.pick(scanTitle, null)?.let { viewModel.onEvent(HomeEvent.ScanFolderChosen(it)) }
            HomeCommand.NewWorkspace -> viewModel.onEvent(HomeEvent.DialogSet(HomeDialog.NewWorkspace()))
            null -> return@LaunchedEffect
        }
        onCommandHandled()
    }
    LaunchedEffect(welcomeRequested) {
        if (welcomeRequested) {
            viewModel.onEvent(HomeEvent.DialogSet(HomeDialog.Welcome))
            onWelcomeHandled()
        }
    }
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is HomeEffect.Open -> onOpen(effect.root)
            is HomeEffect.OpenAll -> onOpenAll(effect.roots)
        }
    }
    HomeContent(state, viewModel::onEvent, onSettings, onAbout, onSearch)
}

/** The Home destination: its view model from Koin, in a store of its own. */
@Composable
public fun HomeDestination(
    onOpen: (Path) -> Unit,
    onOpenAll: (List<Path>) -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onSearch: () -> Unit = {},
    welcomeRequested: Boolean = false,
    onWelcomeHandled: () -> Unit = {},
    command: HomeCommand? = null,
    onCommandHandled: () -> Unit = {},
) {
    val viewModel = koinViewModel<HomeViewModel>(viewModelStoreOwner = rememberDestinationOwner("home"))
    HomePage(
        viewModel, onOpen, onOpenAll, onSettings, onAbout, onSearch,
        welcomeRequested, onWelcomeHandled, command, onCommandHandled,
    )
}

/** Home without its view model, for screenshot tests. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun HomeContent(
    state: HomeState,
    onEvent: (HomeEvent) -> Unit,
    onSettings: () -> Unit = {},
    onAbout: () -> Unit = {},
    onSearch: () -> Unit = {},
) {
    val colors = Camouflage.colors
    val picker = LocalFolderPicker.current
    val openTitle = graftText("home.open.title", "Choose a repository's folder")
    val drop =
        remember(onEvent) {
            object : DragAndDropTarget {
                override fun onDrop(event: DragAndDropEvent): Boolean {
                    val files = runCatching {
                        event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
                    }.getOrNull()
                    val folder = files?.firstOrNull()?.let { (it as? java.io.File)?.toPath() } ?: return false
                    onEvent(HomeEvent.OpenFolder(folder))
                    return true
                }
            }
        }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.surface)
            .dragAndDropTarget(
                shouldStartDragAndDrop = { it.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) },
                target = drop,
            ).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown ||
                    !(event.isMetaPressed || event.isCtrlPressed)
                ) {
                    return@onPreviewKeyEvent false
                }
                when {
                    event.key == Key.O && event.isShiftPressed -> onEvent(HomeEvent.DialogSet(HomeDialog.Clone()))
                    event.key == Key.O -> picker.pick(openTitle, null)?.let { onEvent(HomeEvent.OpenFolder(it)) }
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        TopBar(state, onEvent, onSettings, onAbout, onSearch)
        if (state.readOnly) {
            CamoBanner(
                graftText(
                    "home.readOnly",
                    "These lists were saved by a newer Graft, so they're shown as they are and can't be changed here.",
                ),
                modifier = Modifier.fillMaxWidth(),
                tone = Tone.Warning,
            )
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            SideList(state, onEvent)
            Box(Modifier.width(1.dp).fillMaxHeight().background(colors.outlineVariant))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (state.phase) {
                    HomeState.Phase.Loading -> Skeleton()
                    HomeState.Phase.Failed -> Failed(state.error, onEvent)
                    HomeState.Phase.Ready -> Main(state, onEvent)
                }
            }
        }
    }
    Dialogs(state, onEvent)
}

@Composable
private fun TopBar(
    state: HomeState,
    onEvent: (HomeEvent) -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onSearch: () -> Unit,
) {
    val picker = LocalFolderPicker.current
    val density = Graft.tokens.density
    val openTitle = graftText("home.open.title", "Choose a repository's folder")
    Row(
        Modifier.fillMaxWidth().height(
            density.control + 16.dp,
        ).background(Camouflage.colors.surfaceContainerLow).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CamoText("Graft", style = Graft.tokens.denseStrong, modifier = Modifier.padding(end = 8.dp))
        CamoSearchField(
            query = state.query,
            onQueryChange = { onEvent(HomeEvent.QueryTyped(it)) },
            onSearch = {},
            placeholder = graftText("home.search", "Search repositories…"),
            modifier = Modifier.width(300.dp).height(density.control),
        )
        Spacer(Modifier.weight(1f))
        val write = !state.readOnly
        CamoButton(
            graftText("home.open", "Open…"),
            onClick = { picker.pick(openTitle, null)?.let { onEvent(HomeEvent.OpenFolder(it)) } },
            modifier = Modifier.height(density.control),
            appearance = Appearance.Outline,
        )
        CamoButton(
            graftText("home.clone", "Clone…"),
            onClick = { onEvent(HomeEvent.DialogSet(HomeDialog.Clone())) },
            modifier = Modifier.height(density.control),
            appearance = Appearance.Outline,
            enabled = write,
        )
        CamoButton(
            graftText("home.init", "Init…"),
            onClick = { onEvent(HomeEvent.DialogSet(HomeDialog.Init())) },
            modifier = Modifier.height(density.control),
            appearance = Appearance.Outline,
            enabled = write,
        )
        val menu =
            listOf(
                CamoMenuItem(graftText("home.menu.search", "Search everywhere…"), onClick = onSearch),
                CamoMenuItem(graftText("home.menu.settings", "Settings…"), onClick = onSettings),
                CamoMenuItem(
                    graftText("home.menu.welcome", "Welcome…"),
                    onClick = { onEvent(HomeEvent.DialogSet(HomeDialog.Welcome)) },
                ),
                CamoMenuItem(graftText("home.menu.about", "About Graft"), onClick = onAbout),
            )
        CamoMenuAnchor(items = menu) { open ->
            CamoButton(
                graftText("home.menu", "Menu") + "  ▾",
                onClick = open,
                modifier = Modifier.height(density.control),
                appearance = Appearance.Ghost,
            )
        }
    }
}

@Composable
private fun SideList(state: HomeState, onEvent: (HomeEvent) -> Unit) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val picker = LocalFolderPicker.current
    val scanTitle = graftText("home.scan.pick", "Choose a folder to scan for repositories")
    Column(Modifier.width(220.dp).fillMaxHeight().background(colors.surfaceContainerLowest).padding(vertical = 8.dp)) {
        SectionLabel(graftText("home.recent", "Recent and pinned"))
        NavRow(graftText("home.all", "All repositories"), state.repositories.size, state.view == HomeView.All) {
            onEvent(HomeEvent.ViewChosen(HomeView.All))
        }
        Spacer(Modifier.height(8.dp))
        SectionLabel(graftText("home.workspaces", "Workspaces"))
        state.workspaces.forEach { w ->
            NavRow(w.name, w.repositories.size, (state.view as? HomeView.Workspace)?.id == w.id, workspace = true) {
                onEvent(HomeEvent.ViewChosen(HomeView.Workspace(w.id)))
            }
        }
        val write = !state.readOnly
        CamoButton(
            graftText("home.newWorkspace", "New workspace"),
            onClick = { onEvent(HomeEvent.DialogSet(HomeDialog.NewWorkspace())) },
            modifier = Modifier.padding(horizontal = 8.dp).height(tokens.density.control),
            appearance = Appearance.Ghost,
            enabled = write,
        )
        CamoButton(
            graftText("home.scan", "Scan a folder…"),
            onClick = { picker.pick(scanTitle, null)?.let { onEvent(HomeEvent.ScanFolderChosen(it)) } },
            modifier = Modifier.padding(horizontal = 8.dp).height(tokens.density.control),
            appearance = Appearance.Ghost,
            enabled = write,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    CamoText(
        text.uppercase(),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        style = Graft.tokens.denseStrong,
        color = Camouflage.colors.onSurfaceVariant,
    )
}

@Composable
private fun NavRow(label: String, count: Int, selected: Boolean, workspace: Boolean = false, onClick: () -> Unit) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val spoken =
        if (workspace) {
            graftText(
                "home.workspace.label",
                "{name}, workspace, {count} repositories",
                "name" to label,
                "count" to count,
            )
        } else {
            graftText("home.all.label", "{name}, {count}", "name" to label, "count" to count)
        }
    Row(
        Modifier
            .fillMaxWidth()
            .height(tokens.density.row)
            .background(if (selected) colors.surfaceContainerHighest else colors.surfaceContainerLowest)
            .pointerInput(label) { detectTapGestures(onTap = { onClick() }) }
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown &&
                    (e.key == Key.Enter || e.key == Key.Spacebar)
                ) {
                    onClick().let { true }
                } else {
                    false
                }
            }.clearAndSetSemantics {
                contentDescription = spoken
                this.selected = selected
            }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CamoText(
            label,
            style = tokens.dense,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        CamoText(count.toString(), style = tokens.dense, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun Skeleton() {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(6) { CamoSkeleton(12.dp, width = (320 + it * 40).dp) }
    }
}

@Composable
private fun Failed(error: Throwable?, onEvent: (HomeEvent) -> Unit) {
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
            CamoButton(graftText("diff.retry", "Retry"), onClick = {
                onEvent(HomeEvent.Retry)
            }, modifier = Modifier.height(Graft.tokens.density.control))
        }
    }
}

@Composable
private fun Main(state: HomeState, onEvent: (HomeEvent) -> Unit) {
    val rows = state.rows
    when {
        state.repositories.isEmpty() && state.view == HomeView.All && state.query.isBlank() -> EmptyHome(state, onEvent)

        state.view is HomeView.Workspace -> Overview(state, onEvent)

        rows.isEmpty() ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CamoText(
                    graftText("home.noMatch", "No repositories match \"{query}\".", "query" to state.query.trim()),
                    style = Graft.tokens.dense,
                    color = Camouflage.colors.onSurfaceVariant,
                )
            }

        else -> RepositoryList(state, onEvent)
    }
}

@Composable
private fun EmptyHome(state: HomeState, onEvent: (HomeEvent) -> Unit) {
    val picker = LocalFolderPicker.current
    val openTitle = graftText("home.open.title", "Choose a repository's folder")
    val scanTitle = graftText("home.scan.pick", "Choose a folder to scan for repositories")
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CamoText(
                graftText("home.empty", "No repositories yet. Open a folder, clone one, or scan a folder for them."),
                style = Graft.tokens.dense,
                color = Camouflage.colors.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val height = Modifier.height(Graft.tokens.density.formControl)
                CamoButton(graftText("home.open", "Open…"), onClick = {
                    picker.pick(openTitle, null)?.let { onEvent(HomeEvent.OpenFolder(it)) }
                }, modifier = height)
                CamoButton(graftText("home.clone", "Clone…"), onClick = {
                    onEvent(HomeEvent.DialogSet(HomeDialog.Clone()))
                }, modifier = height, appearance = Appearance.Outline, enabled = !state.readOnly)
                CamoButton(graftText("home.scan", "Scan a folder…"), onClick = {
                    picker.pick(scanTitle, null)?.let { onEvent(HomeEvent.ScanFolderChosen(it)) }
                }, modifier = height, appearance = Appearance.Outline, enabled = !state.readOnly)
            }
        }
    }
}

@Composable
private fun RepositoryList(state: HomeState, onEvent: (HomeEvent) -> Unit) {
    val colors = Camouflage.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val list = rememberLazyListState()
    val rows = state.rows
    val listLabel = graftText("home.list.label", "Repositories, {count}", "count" to rows.size)
    LaunchedEffect(state.focus) {
        val index = state.focusIndex
        if (index >= 0 &&
            list.layoutInfo.visibleItemsInfo.none { it.index == index }
        ) {
            list.scrollToItem(maxOf(index - 2, 0))
        }
    }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.isMetaPressed ||
                    event.isCtrlPressed
                ) {
                    return@onPreviewKeyEvent false
                }
                val handled: HomeEvent =
                    when (event.key) {
                        Key.DirectionDown -> HomeEvent.Moved(1)
                        Key.DirectionUp -> HomeEvent.Moved(-1)
                        Key.MoveHome -> HomeEvent.Moved(Int.MIN_VALUE / 2)
                        Key.MoveEnd -> HomeEvent.Moved(Int.MAX_VALUE / 2)
                        Key.Enter -> HomeEvent.OpenFocused
                        Key.Spacebar -> HomeEvent.PinFocused
                        Key.Delete -> HomeEvent.RemoveFocusedAsked
                        else -> return@onPreviewKeyEvent false
                    }
                onEvent(handled)
                true
            }.focusable(interactionSource = interaction)
            .semantics { contentDescription = listLabel }
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
        items(rows, key = { it.key }) { row ->
            LaunchedEffect(row.key) { onEvent(HomeEvent.RowShown(row.path)) }
            RepositoryRow(row, state, row.key == state.focus, onEvent)
        }
    }
}

@Composable
private fun RepositoryRow(row: HomeRow, state: HomeState, focused: Boolean, onEvent: (HomeEvent) -> Unit) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val picker = LocalFolderPicker.current
    val summary = state.summaries[row.path]
    val known = row.known
    val age = known?.let { relative(it.lastOpened, state.now) }
    val spoken = repositoryLabel(row, summary, age?.let { ageSpoken(it) })
    val locateTitle = graftText("home.locate.title", "Where did {name} move to?", "name" to row.name)
    Row(
        Modifier
            .fillMaxWidth()
            .height(tokens.density.row + 8.dp)
            .background(if (focused) colors.surfaceContainerHighest else colors.surface)
            .pointerInput(row.key) {
                detectTapGestures(
                    onTap = { onEvent(HomeEvent.Focused(row.key)) },
                    onDoubleTap = { if (!row.missing) onEvent(HomeEvent.OpenFolder(row.path)) },
                )
            }.clearAndSetSemantics {
                contentDescription = spoken
                selected = focused
            }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val pin = if (row.pinned) {
            graftText("home.unpin", "Unpin {name}", "name" to row.name)
        } else {
            graftText(
                "home.pin",
                "Pin {name}",
                "name" to row.name,
            )
        }
        CamoIconButton(
            icon = { CamoIcon(graftIcon(GraftIcon.PIN), contentDescription = null, modifier = Modifier.size(14.dp)) },
            contentDescription = pin,
            onClick = { onEvent(HomeEvent.PinToggled(row.path)) },
            modifier = Modifier.size(tokens.density.control),
            appearance = Appearance.Ghost,
            checked = row.pinned,
            tooltip = pin,
            enabled = !state.readOnly && known != null,
        )
        Column(Modifier.weight(1f).alpha(if (row.missing) 0.5f else 1f)) {
            CamoText(row.name, style = tokens.denseStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            CamoText(
                row.path.toString(),
                style = tokens.dense,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.missing) {
            CamoText(graftText("home.missing", "missing"), style = tokens.dense, color = colors.onSurfaceVariant)
            CamoButton(
                graftText("home.locate", "Locate…"),
                onClick = {
                    picker.pick(locateTitle, null)?.let {
                        onEvent(HomeEvent.Located(row.path, it))
                    }
                },
                modifier = Modifier.height(tokens.density.control),
                appearance = Appearance.Outline,
                enabled = !state.readOnly,
            )
            CamoButton(
                graftText("home.remove", "Remove"),
                onClick = { onEvent(HomeEvent.RemoveAsked(row.path)) },
                modifier = Modifier.height(tokens.density.control),
                appearance = Appearance.Ghost,
                enabled = !state.readOnly,
            )
        } else {
            SummaryCells(summary)
            age?.let {
                CamoText(
                    ageShort(it),
                    modifier = Modifier.width(80.dp),
                    style = tokens.dense,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Branch, a dot when there are changes, and ahead/behind. */
@Composable
private fun SummaryCells(summary: SummaryState?) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    when (summary) {
        null, SummaryState.Loading -> CamoSkeleton(10.dp, width = 120.dp)

        is SummaryState.Unavailable -> CamoText("—", style = tokens.dense, color = colors.onSurfaceVariant)

        is SummaryState.Loaded -> {
            val s = summary.summary
            CamoText(
                branchName(s.head),
                modifier = Modifier.width(140.dp),
                style = tokens.dense,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
                if (s.dirty) Box(Modifier.size(8.dp).background(colors.warning, CircleShape))
            }
            CamoText(
                s.upstream?.takeIf { !it.gone }?.let { aheadBehind(it.ahead, it.behind) }.orEmpty(),
                modifier = Modifier.width(70.dp),
                style = tokens.dense,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Overview(state: HomeState, onEvent: (HomeEvent) -> Unit) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val workspace = state.workspace ?: return
    val picker = LocalFolderPicker.current
    val addTitle = graftText("home.workspace.addFolder", "Choose a repository's folder to add")
    val write = !state.readOnly
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(tokens.density.control + 12.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CamoText(workspace.name, style = tokens.denseStrong, modifier = Modifier.weight(1f), maxLines = 1)
            val height = Modifier.height(tokens.density.control)
            CamoButton(graftText("home.openAll", "Open all"), onClick = {
                onEvent(HomeEvent.OpenAll)
            }, modifier = height, enabled = workspace.repositories.isNotEmpty())
            CamoButton(
                graftText("home.addRepositories", "Add repositories…"),
                onClick = { onEvent(HomeEvent.DialogSet(HomeDialog.AddToWorkspace(workspace.id))) },
                modifier = height,
                appearance = Appearance.Outline,
                enabled = write,
            )
            CamoButton(
                graftText("home.addFolder", "Add a folder…"),
                onClick = {
                    picker.pick(addTitle, null)?.let { onEvent(HomeEvent.FolderAddedToWorkspace(workspace.id, it)) }
                },
                modifier = height,
                appearance = Appearance.Outline,
                enabled = write,
            )
            CamoButton(
                graftText("home.rename", "Rename"),
                onClick = { onEvent(HomeEvent.DialogSet(HomeDialog.RenameWorkspace(workspace.id, workspace.name))) },
                modifier = height,
                appearance = Appearance.Ghost,
                enabled = write,
            )
            CamoButton(
                graftText("home.delete", "Delete"),
                onClick = { onEvent(HomeEvent.DialogSet(HomeDialog.DeleteWorkspace(workspace.id, workspace.name))) },
                modifier = height,
                appearance = Appearance.Ghost,
                enabled = write,
            )
        }
        Row(
            Modifier.fillMaxWidth().height(
                tokens.density.row,
            ).background(colors.surfaceContainerLow).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Header(graftText("home.col.repository", "Repository"), Modifier.weight(1f))
            Header(graftText("home.col.branch", "Branch"), Modifier.width(150.dp))
            Header(graftText("home.col.state", "State"), Modifier.width(100.dp))
            Header(graftText("home.col.ahead", "Ahead / behind"), Modifier.width(110.dp))
            Spacer(Modifier.width(tokens.density.control))
        }
        if (workspace.repositories.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CamoText(
                    graftText("home.workspace.empty", "No repositories in this workspace yet."),
                    style = tokens.dense,
                    color = colors.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.rows, key = { it.key }) { row ->
                    LaunchedEffect(row.key) { onEvent(HomeEvent.RowShown(row.path)) }
                    OverviewRow(row, state, workspace.id, onEvent)
                }
            }
        }
    }
}

@Composable
private fun Header(text: String, modifier: Modifier) {
    CamoText(
        text,
        modifier = modifier,
        style = Graft.tokens.denseStrong,
        color = Camouflage.colors.onSurfaceVariant,
        maxLines = 1,
    )
}

@Composable
private fun OverviewRow(row: HomeRow, state: HomeState, workspaceId: String, onEvent: (HomeEvent) -> Unit) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val summary = state.summaries[row.path]
    val spoken = repositoryLabel(row, summary, null)
    val removeLabel = graftText("home.removeFromWorkspace", "Remove {name} from the workspace", "name" to row.name)
    Row(
        Modifier
            .fillMaxWidth()
            .height(tokens.density.row + 6.dp)
            .pointerInput(row.key) {
                detectTapGestures(onDoubleTap = { if (!row.missing) onEvent(HomeEvent.OpenFolder(row.path)) })
            }
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CamoText(
            row.name,
            modifier = Modifier.weight(1f),
            style = tokens.denseStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when {
            row.missing -> CamoText(
                graftText("home.missing", "missing"),
                modifier = Modifier.width(360.dp),
                style = tokens.dense,
                color = colors.onSurfaceVariant,
            )

            summary is SummaryState.Loaded -> {
                val s = summary.summary
                CamoText(
                    branchName(s.head),
                    modifier = Modifier.width(150.dp),
                    style = tokens.dense,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                CamoText(
                    if (s.dirty) graftText("home.dirty", "● changes") else graftText("home.clean", "clean"),
                    modifier = Modifier.width(100.dp),
                    style = tokens.dense,
                    color = if (s.dirty) colors.warning else colors.onSurfaceVariant,
                )
                CamoText(
                    s.upstream?.takeIf {
                        !it.gone
                    }?.let {
                        aheadBehind(it.ahead, it.behind)
                    }.orEmpty(),
                    modifier = Modifier.width(110.dp),
                    style = tokens.dense,
                    color = colors.onSurfaceVariant,
                )
            }

            summary is SummaryState.Unavailable -> CamoText(
                "—",
                modifier = Modifier.width(360.dp),
                style = tokens.dense,
                color = colors.onSurfaceVariant,
            )

            else -> CamoSkeleton(10.dp, width = 240.dp, modifier = Modifier.width(360.dp))
        }
        CamoIconButton(
            icon = { CamoIcon(graftIcon(GraftIcon.CLOSE), contentDescription = null, modifier = Modifier.size(12.dp)) },
            contentDescription = removeLabel,
            onClick = { onEvent(HomeEvent.WorkspaceRepositoryRemoved(workspaceId, row.path)) },
            modifier = Modifier.size(tokens.density.control),
            appearance = Appearance.Ghost,
            tooltip = removeLabel,
            enabled = !state.readOnly,
        )
    }
}

@Composable
private fun Dialogs(state: HomeState, onEvent: (HomeEvent) -> Unit) {
    val close = { onEvent(HomeEvent.DialogClosed) }
    when (val d = state.dialog) {
        HomeDialog.None -> Unit

        HomeDialog.Welcome -> {
            val picker = LocalFolderPicker.current
            val openTitle = graftText("home.open.title", "Choose a repository's folder")
            val scanTitle = graftText("home.scan.pick", "Choose a folder to scan for repositories")
            OnboardingDialog(
                onClose = close,
                onOpenClone = { onEvent(HomeEvent.DialogSet(HomeDialog.Clone())) },
                onOpenFolder = { picker.pick(openTitle, null)?.let { onEvent(HomeEvent.OpenFolder(it)) } },
                onScan = { picker.pick(scanTitle, null)?.let { onEvent(HomeEvent.ScanFolderChosen(it)) } },
            )
        }

        is HomeDialog.Clone -> CloneDialog(d.folder, onCloned = { onEvent(HomeEvent.Created(it)) }, onClose = close)

        is HomeDialog.Init ->
            InitDialog(d.folder, onCreated = {
                onEvent(HomeEvent.Created(it))
            }, onOpenExisting = { onEvent(HomeEvent.OpenFolder(it)) }, onClose = close)

        else ->
            CamoDialogFrame(onDismissRequest = close) {
                SmallDialog(d, state, onEvent, close)
            }
    }
}

@Composable
private fun SmallDialog(dialog: HomeDialog, state: HomeState, onEvent: (HomeEvent) -> Unit, close: () -> Unit) {
    val cancel: @Composable () -> Unit = {
        CamoButton(graftText("clone.cancel", "Cancel"), onClick = close, appearance = Appearance.Ghost)
    }
    when (dialog) {
        is HomeDialog.NotARepository ->
            CamoDialog(
                message = graftText(
                    "home.notRepo.message",
                    "{folder} isn't a repository yet.",
                    "folder" to dialog.folder.toString(),
                ),
                title = graftText("home.notRepo.title", "Not a repository"),
                confirm = {
                    CamoButton(graftText("home.notRepo.init", "Init here"), onClick = {
                        onEvent(HomeEvent.DialogSet(HomeDialog.Init(dialog.folder)))
                    })
                },
                dismiss = {
                    CamoButton(graftText("home.notRepo.clone", "Clone here"), onClick = {
                        onEvent(HomeEvent.DialogSet(HomeDialog.Clone(dialog.folder)))
                    }, appearance = Appearance.Outline)
                },
            )

        is HomeDialog.Trust ->
            CamoDialog(
                message = graftText(
                    "home.trust.message",
                    "Git won't open {folder} because another user owns it. Trusting it makes this change to your Git settings:",
                    "folder" to dialog.outcome.folder,
                ),
                title = graftText("home.trust.title", "Trust this folder?"),
                confirm = {
                    CamoButton(graftText("home.trust.confirm", "Trust this folder"), onClick = {
                        onEvent(HomeEvent.TrustConfirmed)
                    })
                },
                dismiss = cancel,
            ) {
                BasicText(
                    dialog.outcome.command.joinToString(" "),
                    style = Graft.tokens.code.copy(color = Camouflage.colors.onSurface),
                )
            }

        is HomeDialog.NewWorkspace -> NameDialog(graftText("home.new.title", "New workspace"), dialog.name, {
            onEvent(HomeEvent.DialogSet(dialog.copy(name = it)))
        }, graftText("home.new.confirm", "Create workspace"), { onEvent(HomeEvent.NewWorkspaceConfirmed) }, cancel)

        is HomeDialog.RenameWorkspace -> NameDialog(graftText("home.rename.title", "Rename workspace"), dialog.name, {
            onEvent(HomeEvent.DialogSet(dialog.copy(name = it)))
        }, graftText("home.rename.confirm", "Rename"), { onEvent(HomeEvent.RenameConfirmed) }, cancel)

        is HomeDialog.DeleteWorkspace ->
            CamoDialog(
                message = graftText(
                    "home.deleteWorkspace.message",
                    "The workspace {name} goes away. Its repositories stay in Home and on disk.",
                    "name" to dialog.name,
                ),
                title = graftText("home.deleteWorkspace.title", "Delete workspace?"),
                confirm = {
                    CamoButton(graftText("home.deleteWorkspace.confirm", "Delete workspace"), onClick = {
                        onEvent(HomeEvent.DeleteWorkspaceConfirmed)
                    }, tone = Tone.Error)
                },
                dismiss = cancel,
            )

        is HomeDialog.RemoveRepository ->
            CamoDialog(
                message = graftText(
                    "home.removeRepo.message",
                    "{name} is removed from Home's list. The folder isn't touched.",
                    "name" to (dialog.path.fileName?.toString() ?: dialog.path.toString()),
                ),
                title = graftText("home.removeRepo.title", "Remove from Home?"),
                confirm = {
                    CamoButton(graftText("home.remove", "Remove"), onClick = {
                        onEvent(HomeEvent.RemoveConfirmed)
                    }, tone = Tone.Error)
                },
                dismiss = cancel,
            )

        is HomeDialog.Scan -> ScanDialog(dialog, onEvent, cancel)

        is HomeDialog.AddToWorkspace -> AddDialog(dialog, state, onEvent, cancel)

        is HomeDialog.Problem -> ProblemDialog(dialog, onEvent, close)

        else -> Unit
    }
}

@Composable
private fun NameDialog(
    title: String,
    name: String,
    onName: (String) -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    cancel: @Composable () -> Unit,
) {
    CamoDialog(
        message = graftText("home.name.message", "Give it a name."),
        title = title,
        confirm = { CamoButton(confirmText, onClick = onConfirm, enabled = name.isNotBlank()) },
        dismiss = cancel,
    ) {
        CamoTextField(name, onName, modifier = Modifier.fillMaxWidth(), label = graftText("home.name", "Name"))
    }
}

@Composable
private fun ScanDialog(dialog: HomeDialog.Scan, onEvent: (HomeEvent) -> Unit, cancel: @Composable () -> Unit) {
    val found = dialog.found
    CamoDialog(
        message = graftText("home.scan.message", "Repositories under {folder}", "folder" to dialog.folder.toString()),
        title = graftText("home.scan.title", "Scan a folder"),
        modifier = Modifier.width(560.dp),
        confirm = {
            CamoButton(
                graftText("home.scan.add", "Add selected ({count})", "count" to dialog.selected.size),
                onClick = { onEvent(HomeEvent.ScanConfirmed) },
                enabled = !dialog.scanning && dialog.selected.isNotEmpty(),
            )
        },
        dismiss = cancel,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (dialog.scanning) {
                CamoText(
                    graftText("home.scan.scanning", "Scanning…"),
                    style = Graft.tokens.dense,
                    color = Camouflage.colors.onSurfaceVariant,
                )
            } else if (found.isEmpty()) {
                CamoText(
                    graftText("home.scan.none", "No repositories found in this folder."),
                    style = Graft.tokens.dense,
                    color = Camouflage.colors.onSurfaceVariant,
                )
            } else {
                CamoCheckbox(
                    dialog.selected.size == found.size,
                    { onEvent(HomeEvent.ScanAllToggled) },
                    label = graftText("home.scan.all", "Select all ({count})", "count" to found.size),
                )
                LazyColumn(Modifier.height(220.dp).fillMaxWidth()) {
                    items(found, key = { it.path.toString() }) { r ->
                        val note = if (r.known) " " + graftText("home.scan.known", "(already in Home)") else ""
                        CamoCheckbox(
                            r.path in dialog.selected,
                            { onEvent(HomeEvent.ScanToggled(r.path)) },
                            label =
                            r.path.toString() + note,
                        )
                    }
                }
                CamoTextField(
                    dialog.workspaceName,
                    { onEvent(HomeEvent.DialogSet(dialog.copy(workspaceName = it))) },
                    modifier = Modifier.fillMaxWidth(),
                    label = graftText("home.scan.workspace", "Also save them as a workspace (optional)"),
                    placeholder = graftText("home.name", "Name"),
                )
            }
        }
    }
}

@Composable
private fun AddDialog(
    dialog: HomeDialog.AddToWorkspace,
    state: HomeState,
    onEvent: (HomeEvent) -> Unit,
    cancel: @Composable () -> Unit,
) {
    val members = state.workspaces.firstOrNull { it.id == dialog.id }?.repositories.orEmpty().toSet()
    val candidates = state.repositories.filter { it.path !in members && !it.missing }
    CamoDialog(
        message = graftText("home.add.message", "Choose repositories Home already lists."),
        title = graftText("home.addRepositories", "Add repositories…"),
        confirm = {
            CamoButton(
                graftText("home.add.confirm", "Add {count}", "count" to dialog.selected.size),
                onClick = { onEvent(HomeEvent.AddToWorkspaceConfirmed) },
                enabled = dialog.selected.isNotEmpty(),
            )
        },
        dismiss = cancel,
    ) {
        if (candidates.isEmpty()) {
            CamoText(
                graftText("home.add.none", "Every repository in Home is in this workspace already."),
                style = Graft.tokens.dense,
                color = Camouflage.colors.onSurfaceVariant,
            )
        } else {
            LazyColumn(Modifier.height(220.dp).fillMaxWidth()) {
                items(candidates, key = { it.path.toString() }) { r ->
                    CamoCheckbox(r.path in dialog.selected, {
                        onEvent(HomeEvent.AddToWorkspaceToggled(r.path))
                    }, label = r.path.toString())
                }
            }
        }
    }
}

@Composable
private fun ProblemDialog(dialog: HomeDialog.Problem, onEvent: (HomeEvent) -> Unit, close: () -> Unit) {
    val error = dialog.error
    val text =
        dialog.text?.removePrefix("missing:")?.takeIf { dialog.text.startsWith("missing:") }?.let {
            graftText("home.problem.missing", "{folder} doesn't exist any more.", "folder" to it)
        } ?: dialog.text
    val message = text ?: ((error as? SinewException)?.displayMessage(Graft.localizer) ?: error?.message.orEmpty())
    val code = (error as? SinewException)?.code
    CamoDialog(
        message = if (code != null && code !in message) "$message · $code" else message,
        title = graftText("home.problem.title", "Couldn't do that"),
        confirm = { CamoButton(graftText("graph.output.close", "Close"), onClick = close) },
        dismiss =
        if (error is CommandOutput) {
            {
                CamoButton(
                    if (dialog.outputShown) {
                        graftText(
                            "home.problem.hide",
                            "Hide output",
                        )
                    } else {
                        graftText("graph.showOutput", "Show output")
                    },
                    onClick = { onEvent(HomeEvent.ProblemOutputShown(!dialog.outputShown)) },
                    appearance = Appearance.Ghost,
                )
            }
        } else {
            null
        },
    ) {
        if (dialog.outputShown && error is CommandOutput) {
            BasicText(error.stderr, style = Graft.tokens.code.copy(color = Camouflage.colors.onSurface))
        }
    }
}

// Branch, state and ahead/behind as text, and the row's one spoken label.

private fun branchName(head: Head): String = when (head) {
    is Head.Branch -> head.name
    is Head.Detached -> "detached " + head.commit.short()
}

private fun aheadBehind(ahead: Int, behind: Int): String = "↑$ahead ↓$behind"

/** How long ago a repository was opened (the same units the graph's rows use, kept here since features don't import each other). */
internal sealed interface Opened {
    data object JustNow : Opened

    data class Minutes(val n: Long) : Opened

    data class Hours(val n: Long) : Opened

    data class Days(val n: Long) : Opened

    data class Date(val text: String) : Opened
}

internal fun relative(epochSeconds: Long, nowSeconds: Long): Opened {
    val s = nowSeconds - epochSeconds
    return when {
        nowSeconds <= 0 || s < 60 -> Opened.JustNow

        s < 3_600 -> Opened.Minutes(s / 60)

        s < 86_400 -> Opened.Hours(s / 3_600)

        s < 7 * 86_400 -> Opened.Days(s / 86_400)

        else -> Opened.Date(
            java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
                .format(java.time.Instant.ofEpochSecond(epochSeconds).atZone(java.time.ZoneId.systemDefault())),
        )
    }
}

@Composable
private fun ageShort(o: Opened): String = when (o) {
    Opened.JustNow -> graftText("graph.age.now", "just now")
    is Opened.Minutes -> graftText("graph.age.minutes", "{count} min ago", "count" to o.n)
    is Opened.Hours -> graftText("graph.age.hours", "{count} h ago", "count" to o.n)
    is Opened.Days -> graftText("graph.age.days", "{count} d ago", "count" to o.n)
    is Opened.Date -> o.text
}

@Composable
private fun ageSpoken(o: Opened): String = when (o) {
    Opened.JustNow -> graftText("graph.age.now", "just now")
    is Opened.Minutes -> graftText("graph.ageLong.minutes", "{count} minutes ago", "count" to o.n)
    is Opened.Hours -> graftText("graph.ageLong.hours", "{count} hours ago", "count" to o.n)
    is Opened.Days -> graftText("graph.ageLong.days", "{count} days ago", "count" to o.n)
    is Opened.Date -> o.text
}

/** "app, ~/Projects/app, branch main, uncommitted changes, 1 ahead, opened 2 hours ago, pinned". */
@Composable
internal fun repositoryLabel(row: HomeRow, summary: SummaryState?, opened: String?): String {
    val parts = mutableListOf(row.name, row.path.toString())
    if (row.missing) {
        parts += graftText("home.missing", "missing")
    } else if (summary is SummaryState.Loaded) {
        val s = summary.summary
        parts += graftText("home.label.branch", "branch {name}", "name" to branchName(s.head))
        if (s.dirty) parts += graftText("home.label.dirty", "uncommitted changes")
        s.upstream?.takeIf { !it.gone }?.let {
            if (it.ahead > 0) parts += graftText("ui.ref.ahead", "{count} ahead", "count" to it.ahead)
            if (it.behind > 0) parts += graftText("ui.ref.behind", "{count} behind", "count" to it.behind)
        }
    }
    opened?.let { parts += graftText("home.label.opened", "opened {when}", "when" to it) }
    if (row.pinned) parts += graftText("branches.pinned", "pinned")
    return parts.joinToString(", ")
}
