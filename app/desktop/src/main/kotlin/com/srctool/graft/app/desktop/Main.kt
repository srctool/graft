// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.app.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.srctool.graft.app.desktop.di.desktopModules
import com.srctool.graft.app.desktop.session.RepositorySessionsService
import com.srctool.graft.core.configuration.AppFolders
import com.srctool.graft.core.configuration.WindowBounds
import com.srctool.graft.core.configuration.instance.AppInstanceClient
import com.srctool.graft.core.configuration.instance.AppInstanceServer
import com.srctool.graft.core.configuration.instance.InstanceFolder
import com.srctool.graft.core.configuration.instance.InstanceRequest
import com.srctool.graft.core.configuration.instance.InstanceResponse
import com.srctool.graft.core.ui.theme.GraftDensity
import com.srctool.graft.core.ui.theme.GraftTheme
import com.srctool.graft.core.ui.theme.GraftThemeMode
import com.srctool.graft.data.app.api.models.Density
import com.srctool.graft.data.app.api.models.SavedWindow
import com.srctool.graft.data.app.api.models.ThemeChoice
import com.srctool.graft.data.app.api.repositories.SettingsRepository
import com.srctool.graft.data.app.api.repositories.WindowStateRepository
import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.data.git.api.repositories.RepositoryAccessRepository
import com.srctool.graft.navigation.shell.MainWindow
import com.srctool.graft.navigation.shell.restoreTabs
import com.srctool.graft.navigation.shell.toSaved
import com.srctool.graft.presentation.window.WindowState
import com.srctool.sinew.models.Result
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import org.koin.compose.koinInject
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform
import java.awt.GraphicsEnvironment
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

private val DEFAULT_SIZE = DpSize(1280.dp, 900.dp)
private const val SAVE_PAUSE_MS = 500L
private const val HANDOFF_WAIT_MS = 3_000L
private val MAC = System.getProperty("os.name").orEmpty().startsWith("Mac")

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
fun main(args: Array<String>) {
    // One instance (Platform Integration 3.5): a second launch hands its folders to the first and exits.
    val instance = InstanceFolder.of(
        AppFolders.current(),
        Paths.get(System.getProperty("java.io.tmpdir")),
        System.getProperty("user.name").orEmpty(),
    )
    val incoming = Channel<List<String>>(Channel.UNLIMITED)
    val server =
        when (val bound = runBlocking { AppInstanceServer.bind(instance) { request -> accept(request, incoming) } }) {
            is Result.Success -> bound.data ?: handOff(instance, args)

            // Without the socket the app still runs, only without `graft open` reaching it.
            is Result.Failure -> {
                System.err.println("Single instance is off: ${bound.error.code}")
                null
            }
        }
    server?.let { Runtime.getRuntime().addShutdownHook(Thread { it.close() }) }
    // The folders this launch was given open as tabs once the window is up.
    if (args.isNotEmpty()) incoming.trySend(args.toList())

    // ADR-28: everything the screens need is wired once, here.
    startKoin { modules(desktopModules()) }
    val koin = KoinPlatform.getKoin()
    val windows = koin.get<WindowState>()
    val sessions = koin.get<RepositorySessionsService>()
    val saved = koin.get<WindowStateRepository>()
    val access = koin.get<RepositoryAccessRepository>()

    val locate: suspend (Path) -> RepositoryLocation? = { folder -> (access.locate(folder) as? Result.Success)?.data }
    val windowId = windows.openWindow()
    val last = runBlocking { (saved.windows() as? Result.Success)?.data?.firstOrNull() }
    // Home first, then the repository tabs the window had; a folder that's gone is skipped and said so.
    val missing = runBlocking { last?.let { windows.restoreTabs(windowId, it, locate) }.orEmpty() }
    // A developer's shortcut: GRAFT_REPO opens that folder as a tab on top of what the window restored.
    System.getenv("GRAFT_REPO")?.let { folder ->
        runBlocking { locate(Paths.get(folder)) }?.let { windows.openRepository(windowId, it) }
    }

    application {
        val screens = screens()
        val place = last?.let { clampedBounds(it, screens) }
        val state =
            rememberWindowState(
                position = place?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition.PlatformDefault,
                size = place?.let { DpSize(it.width.dp, it.height.dp) } ?: DEFAULT_SIZE,
                placement = if (last?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
            )
        // The bounds to save are the window's floating ones: a maximized window remembers where it'd come back to.
        var floating = place ?: WindowBounds(0, 0, DEFAULT_SIZE.width.value.toInt(), DEFAULT_SIZE.height.value.toInt())

        fun current(): SavedWindow {
            if (state.placement == WindowPlacement.Floating) {
                val at = state.position
                floating =
                    WindowBounds(
                        if (at.isSpecified) at.x.value.toInt() else floating.x,
                        if (at.isSpecified) at.y.value.toInt() else floating.y,
                        state.size.width.value.toInt(),
                        state.size.height.value.toInt(),
                    )
            }
            return windows.toSaved(
                windowId,
                floating.x,
                floating.y,
                floating.width,
                floating.height,
                state.placement == WindowPlacement.Maximized,
            )
        }

        LaunchedEffect(Unit) {
            sessions.listen(this)
            // Every change of place, size or tabs is saved after a pause, so a drag is one write.
            combine(snapshotFlow { current() }, windows.windows) { window, _ -> window }
                .debounce(SAVE_PAUSE_MS)
                .collect { saved.save(listOf(it)) }
        }
        Window(
            onCloseRequest = {
                runBlocking {
                    saved.save(listOf(current()))
                    sessions.destroy()
                }
                server?.close()
                exitApplication()
            },
            title = "Graft",
            state = state,
        ) {
            if (MAC) {
                // Full-height content under a transparent title bar; the traffic lights stay (Platform Integration 3.6).
                window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
            }
            // A window that comes to the front reads its repositories again: nothing guarantees a watcher saw every
            // change while the app was in the background.
            val info = LocalWindowInfo.current
            LaunchedEffect(info) {
                snapshotFlow { info.isWindowFocused }.collect { if (it) sessions.windowFocused(windowId) }
            }
            // The theme and density follow Settings as they change (Settings writes publish through `changes`).
            val settings = koinInject<SettingsRepository>()
            val current by settings.changes.collectAsState()
            LaunchedEffect(settings) { settings.read() }
            GraftTheme(
                mode = current?.theme.toThemeMode(),
                density = if (current?.density ==
                    Density.COMFORTABLE
                ) {
                    GraftDensity.Comfortable
                } else {
                    GraftDensity.Standard
                },
            ) {
                // A request raises the window as well as opening the tab.
                val requests =
                    remember(incoming) {
                        incoming.receiveAsFlow().onEach {
                            state.isMinimized = false
                            window.toFront()
                            window.requestFocus()
                        }.map { paths -> paths.map(Paths::get) }
                    }
                MainWindow(windows, windowId, sessions, missing, macTitleBar = MAC, requests = requests)
            }
        }
    }
}

/** What the running app is told: open the folders and raise the window; a ping only answers. */
private fun accept(request: InstanceRequest, incoming: Channel<List<String>>): InstanceResponse {
    if (request.command == InstanceRequest.OPEN) incoming.trySend(request.paths)
    return InstanceResponse(ok = true)
}

/** Another instance is running: gives it this launch's folders and exits (with 1 when it can't be reached). */
private fun handOff(instance: InstanceFolder, args: Array<String>): Nothing {
    val answer =
        runBlocking {
            AppInstanceClient(instance).send(InstanceRequest.OPEN, args.toList(), waitMillis = HANDOFF_WAIT_MS)
        }
    exitProcess(if ((answer as? Result.Success)?.data?.ok == true) 0 else 1)
}

/** The saved bounds moved onto a screen that exists now (Platform Integration 3.6). */
private fun clampedBounds(saved: SavedWindow, screens: List<WindowBounds>): WindowBounds =
    com.srctool.graft.core.configuration.WindowPlacement.clamp(
        WindowBounds(saved.x, saved.y, saved.width, saved.height),
        screens,
    )

private fun screens(): List<WindowBounds> = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map {
    it.defaultConfiguration.bounds.let { b -> WindowBounds(b.x, b.y, b.width, b.height) }
}

/** Until Settings has been read, follow the OS: no flash of the wrong theme for a user who chose System. */
private fun ThemeChoice?.toThemeMode(): GraftThemeMode = when (this) {
    ThemeChoice.LIGHT -> GraftThemeMode.LIGHT
    ThemeChoice.DARK -> GraftThemeMode.DARK
    else -> GraftThemeMode.SYSTEM
}
