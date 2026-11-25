// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.skeleton.CamoSkeleton
import com.srctool.graft.core.ui.components.DockSide
import com.srctool.graft.core.ui.components.DocumentTab
import com.srctool.graft.core.ui.components.DocumentTabs
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.GraftIcon
import com.srctool.graft.data.git.api.models.LogQuery
import com.srctool.graft.data.git.api.models.LogScope
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.data.git.api.repositories.CommitRepository
import com.srctool.graft.data.git.api.repositories.RepositoryAccessRepository
import com.srctool.graft.feature.graph.presentation.repository.PanelIds
import com.srctool.graft.feature.graph.presentation.repository.RepositoryContributions
import com.srctool.graft.feature.graph.presentation.repository.RepositoryScreen
import com.srctool.graft.feature.home.presentation.home.HomeDestination
import com.srctool.graft.feature.home.search.HomeActions
import com.srctool.graft.feature.home.search.HomeCommand
import com.srctool.graft.feature.workingcopy.presentation.changes.rememberWorkingCopyContributions
import com.srctool.graft.navigation.about.AboutDialog
import com.srctool.graft.navigation.search.DoubleShift
import com.srctool.graft.navigation.search.HostActions
import com.srctool.graft.navigation.search.SearchDialog
import com.srctool.graft.navigation.search.SearchTab
import com.srctool.graft.navigation.settings.SettingsDialog
import com.srctool.graft.presentation.search.RepositoryScope
import com.srctool.graft.presentation.search.SearchScope
import com.srctool.graft.presentation.search.SearchTarget
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.session.RepositorySessions
import com.srctool.graft.presentation.view.CenterView
import com.srctool.graft.presentation.view.ViewSelection
import com.srctool.graft.presentation.window.Tab
import com.srctool.graft.presentation.window.TabContent
import com.srctool.graft.presentation.window.WindowState
import com.srctool.sinew.models.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.nio.file.Path

private enum class HostDialog { None, Settings, About, Search, Branches, Actions }

/**
 * The main window (Task 20): a toolbar, the window's tabs, the active tab's screen and the status bar, with Settings,
 * About and Search Everywhere as dialogs of it (D1). [windowId] is this window in [windows]; [sessions] are the
 * repositories' sessions, which the app keeps for the tabs. [missingFolders] are saved tabs the app couldn't restore
 * because the folder is gone; the status bar says so. [macTitleBar] leaves room for the traffic lights in the toolbar.
 *
 * Every tab stays composed while it exists and only the one in front is laid out, so a tab found again has its
 * selection, scroll and panels as they were. Each tab has its own Navigation 3 back stack and view-model stores.
 */
@Composable
public fun MainWindow(
    windows: WindowState,
    windowId: String,
    sessions: RepositorySessions,
    missingFolders: List<String> = emptyList(),
    macTitleBar: Boolean = false,
    /** Folders another launch (`graft open`) asks this window to open; each opens as a tab, or says it isn't one. */
    requests: Flow<List<Path>> = emptyFlow(),
) {
    val access = koinInject<RepositoryAccessRepository>()
    val commits = koinInject<CommitRepository>()
    val coroutines = rememberCoroutineScope()
    val shell =
        remember(windows, windowId) {
            ShellState(windows, windowId, locate = { folder -> (access.locate(folder) as? Result.Success)?.data })
        }
    val all by windows.windows.collectAsState()
    val window = all.firstOrNull { it.id == windowId } ?: return
    // A tab that closed takes its stack and layout with it.
    shell.prune()

    var dialog by remember { mutableStateOf(HostDialog.None) }
    var welcomeRequested by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf<HomeCommand?>(null) }
    val missingText = missingFolders.firstOrNull()?.let {
        graftText(
            "shell.missing",
            "A saved repository wasn't found: {folder}",
            "folder" to it,
        )
    }
    var notice by remember(missingFolders) { mutableStateOf(missingText) }
    val notFound = graftText("shell.notFound", "No repository found in {folder}", "folder" to "%")
    val scopes = remember { mutableStateMapOf<String, RepositoryScope?>() }
    val doubleShift = remember { DoubleShift() }
    val live by sessions.sessions.collectAsState()

    val activeTab = window.activeTab
    val activeScope = scopes[activeTab.id]
    val activeLocation = (activeTab.content as? TabContent.Repository)?.location
    val activeSnapshot = activeLocation?.let { live[it] }?.snapshot?.collectAsState()?.value
    val status = statusOf(activeSnapshot)

    fun open(folder: Path) {
        coroutines.launch {
            if (!shell.open(folder)) {
                notice = notFound.replace("%", folder.toString())
            }
        }
    }

    LaunchedEffect(requests) { requests.collect { folders -> folders.forEach(::open) } }

    fun select(id: ObjectId) {
        activeScope?.view?.let {
            it.center.value = CenterView.Graph
            it.selection.value = ViewSelection.Commit(id)
        }
    }

    fun goHome() = shell.goHome()

    fun activate(target: SearchTarget) {
        when (target) {
            is SearchTarget.OpenRepository -> open(target.root)

            is SearchTarget.SelectCommit -> select(target.id)

            // The newest commit that changed the file: G1 has no file viewer, so its change is what there is to show.
            is SearchTarget.FileInHead -> {
                val folder = activeScope?.session?.location?.let { it.workTree ?: it.gitDir } ?: return
                coroutines.launch {
                    val log = commits.search(folder, LogQuery(path = target.path), LogScope.All())
                    log.rows.firstOrNull()?.let { select(it.id) }
                }
            }

            is SearchTarget.RunAction ->
                when (target.id) {
                    HostActions.SETTINGS -> dialog = HostDialog.Settings

                    HostActions.ABOUT -> dialog = HostDialog.About

                    HostActions.WELCOME -> {
                        goHome()
                        welcomeRequested = true
                    }

                    HostActions.HOME -> goHome()

                    else -> HomeActions.command(target.id)?.let {
                        // Home's own commands run on Home.
                        goHome()
                        command = it
                    }
                }
        }
    }

    fun toggleSide(side: DockSide, window: String) {
        if (activeTab.content is TabContent.Repository) shell.dock(activeTab).toggleSideOf(side, window)
    }

    fun back() {
        val view = activeScope?.view
        when (val center = view?.center?.value) {
            is CenterView.Diff -> view.center.value = center.back
            is CenterView.Compare -> view.center.value = CenterView.Graph
            else -> shell.back(activeTab.id)
        }
    }

    // The window's own focus target, so its shortcuts work from the first moment; screens take focus from it as needed.
    val windowFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { windowFocus.requestFocus() }
    Column(
        Modifier.fillMaxSize().background(
            Camouflage.colors.surface,
        ).focusRequester(windowFocus).focusable().onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val mod = event.isMetaPressed || event.isCtrlPressed
            val shift = event.key == Key.ShiftLeft || event.key == Key.ShiftRight
            if (doubleShift.onKeyDown(shift, mod || event.isAltPressed, System.currentTimeMillis())) {
                dialog = HostDialog.Search
                return@onPreviewKeyEvent true
            }
            val panelKey = if (IS_MAC) event.isMetaPressed else event.isAltPressed
            when {
                mod && event.key == Key.T -> goHome()

                mod && event.key == Key.W -> shell.close(activeTab.id)

                // Open and Clone are Home's commands: Home comes forward and runs them.
                mod && event.key == Key.O -> {
                    goHome()
                    command = if (event.isShiftPressed) HomeCommand.Clone else HomeCommand.Open
                }

                mod && event.key == Key.Comma -> dialog = HostDialog.Settings

                mod && event.isShiftPressed && event.key == Key.A -> dialog = HostDialog.Actions

                mod && event.isShiftPressed && event.key == Key.F12 ->
                    if (activeTab.content is TabContent.Repository) shell.dock(activeTab).toggleFocusMode()

                mod && event.isShiftPressed && event.key == Key.RightBracket -> shell.step(1)

                mod && event.isShiftPressed && event.key == Key.LeftBracket -> shell.step(-1)

                event.isCtrlPressed && event.key == Key.Tab -> shell.step(if (event.isShiftPressed) -1 else 1)

                panelKey && !event.isShiftPressed && event.key == Key.One -> toggleSide(
                    DockSide.LEFT,
                    PanelIds.BRANCHES,
                )

                panelKey && !event.isShiftPressed && event.key == Key.Two -> toggleSide(
                    DockSide.RIGHT,
                    PanelIds.DETAILS,
                )

                panelKey && !event.isShiftPressed && event.key == Key.Three ->
                    toggleSide(DockSide.BOTTOM, PanelIds.OPERATIONS)

                event.isAltPressed && event.key == Key.DirectionLeft -> back()

                else -> return@onPreviewKeyEvent false
            }
            true
        },
    ) {
        Toolbar(
            repositories =
            window.tabs.filter {
                it.content is TabContent.Repository
            }.map { OpenRepositoryTab(it.id, tabTitle(window.tabs, it)) },
            repository = (activeTab.content as? TabContent.Repository)?.let { tabTitle(window.tabs, activeTab) },
            branch = status.branch,
            actions =
            ToolbarActions(
                onHome = ::goHome,
                onPick = shell::activate,
                onBranch = { dialog = HostDialog.Branches },
                onSearch = { dialog = HostDialog.Search },
                onSettings = { dialog = HostDialog.Settings },
                onWelcome = {
                    goHome()
                    welcomeRequested = true
                },
                onAbout = { dialog = HostDialog.About },
                onFocusMode = {
                    if (activeTab.content is TabContent.Repository) shell.dock(activeTab).toggleFocusMode()
                },
                onGitLog = { toggleSide(DockSide.BOTTOM, PanelIds.GIT_LOG) },
            ),
            leadingInset = if (macTitleBar) MAC_TRAFFIC_LIGHTS else 0.dp,
        )
        val homeTitle = graftText("tabs.home", "Home")
        DocumentTabs(
            tabs =
            window.tabs.map { tab ->
                val home = tab.content == TabContent.Home
                DocumentTab(
                    id = tab.id,
                    title = if (home) homeTitle else tabTitle(window.tabs, tab),
                    icon = if (home) GraftIcon.HOME else GraftIcon.FOLDER,
                    closable = !home,
                    pinned = home,
                )
            },
            selectedId = window.activeTabId,
            onSelect = shell::activate,
            onClose = shell::close,
            onMove = { _, _ -> },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            window.tabs.forEach { tab ->
                key(tab.id) {
                    val front = tab.id == window.activeTabId
                    Box(Modifier.fillMaxSize().then(if (front) Modifier else Modifier.unplaced())) {
                        TabScreen(
                            shell = shell,
                            tab = tab,
                            sessions = live,
                            onScope = { scopes[tab.id] = it },
                            onOpen = ::open,
                            onSettings = { dialog = HostDialog.Settings },
                            onAbout = { dialog = HostDialog.About },
                            onSearch = { dialog = HostDialog.Search },
                            welcomeRequested = welcomeRequested,
                            onWelcomeHandled = { welcomeRequested = false },
                            command = command,
                            onCommandHandled = { command = null },
                        )
                    }
                }
            }
        }
        StatusBar(status, notice) { notice = null }
    }
    when (dialog) {
        HostDialog.None -> Unit

        HostDialog.Settings ->
            SettingsDialog(
                onClose = { dialog = HostDialog.None },
                // Welcome opens on Home, so a repository that's open is left first.
                onWelcomeAgain = {
                    dialog = HostDialog.None
                    goHome()
                    welcomeRequested = true
                },
            )

        HostDialog.About -> AboutDialog(onClose = { dialog = HostDialog.None })

        HostDialog.Search, HostDialog.Branches, HostDialog.Actions ->
            SearchDialog(
                scope = SearchScope(activeScope),
                startTab =
                when (dialog) {
                    HostDialog.Actions -> SearchTab.Actions
                    HostDialog.Branches -> SearchTab.Refs
                    else -> SearchTab.All
                },
                onActivate = ::activate,
                onClose = { dialog = HostDialog.None },
            )
    }
}

@Composable
private fun TabScreen(
    shell: ShellState,
    tab: Tab,
    sessions: Map<RepositoryLocation, RepositorySession>,
    onScope: (RepositoryScope?) -> Unit,
    onOpen: (Path) -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onSearch: () -> Unit,
    welcomeRequested: Boolean,
    onWelcomeHandled: () -> Unit,
    command: HomeCommand?,
    onCommandHandled: () -> Unit,
) {
    val stack = shell.stack(tab)
    NavDisplay(
        backStack = stack,
        onBack = { shell.back(tab.id) },
        entryDecorators =
        listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        entryProvider =
        entryProvider<TabKey> {
            entry<TabKey.Home> {
                HomeDestination(
                    onOpen = onOpen,
                    // One window, one tab each: every repository Open all names gets its own tab.
                    onOpenAll = { roots -> roots.forEach(onOpen) },
                    onSettings = onSettings,
                    onAbout = onAbout,
                    onSearch = onSearch,
                    welcomeRequested = welcomeRequested,
                    onWelcomeHandled = onWelcomeHandled,
                    command = command,
                    onCommandHandled = onCommandHandled,
                )
            }
            entry<TabKey.Repository> {
                val location = (tab.content as? TabContent.Repository)?.location
                val session = location?.let { sessions[it] }
                if (session == null) {
                    OpeningRepository()
                } else {
                    RepositoryScreen(
                        session,
                        onViewState = { view -> onScope(view?.let { RepositoryScope(session, it) }) },
                        contribute = { shared ->
                            // The working copy's Changes window and its diff in the center (E3): features never
                            // import each other, so the tab's host joins them.
                            val working = rememberWorkingCopyContributions(session, shared, onOpen)
                            if (working == null) {
                                RepositoryContributions()
                            } else {
                                RepositoryContributions(
                                    listOf(working.window),
                                    mapOf(working.centerId to { contributed -> working.center(contributed.key) }),
                                )
                            }
                        },
                        dock = shell.dock(tab),
                    )
                }
            }
        },
    )
}

/** The tab before its session is up: a skeleton, so the shell never waits for a repository. */
@Composable
private fun OpeningRepository() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopStart) {
        CamoSkeleton(10.dp, width = 320.dp)
    }
}

/** The folder's name, and its parent's too when another open tab has the same name. */
private fun tabTitle(tabs: List<Tab>, tab: Tab): String {
    val root = (tab.content as? TabContent.Repository)?.root ?: return ""
    val name = root.fileName?.toString() ?: root.toString()
    val clash = tabs.count { ((it.content as? TabContent.Repository)?.root?.fileName?.toString()) == name } > 1
    return if (clash) root.parent?.fileName?.let { "$it/$name" } ?: name else name
}

/** Not measured, so not drawn, focused or read, while its composition (and so its state) stays. */
private fun Modifier.unplaced(): Modifier = layout { _, _ -> layout(0, 0) {} }.clearAndSetSemantics {}

private val IS_MAC = System.getProperty("os.name").orEmpty().startsWith("Mac")

/** Room for the traffic lights the transparent title bar leaves in the window's top-left corner. */
private val MAC_TRAFFIC_LIGHTS: Dp = 78.dp
