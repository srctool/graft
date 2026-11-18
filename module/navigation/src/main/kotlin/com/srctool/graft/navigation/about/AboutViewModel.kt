// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.about

import com.srctool.graft.data.git.api.models.GitInstallStatus
import com.srctool.graft.data.git.api.repositories.GitInstallRepository
import com.srctool.sinew.viewmodel.EventActionViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class AboutState(
    val version: String = "",
    /** git's version as it ran, or null when it isn't there or can't be read. */
    val git: String? = null,
    val system: SystemInfo = SystemInfo("", ""),
    val licenses: List<LicenseEntry> = emptyList(),
    val query: String = "",
    val selected: Int = 0,
    val loaded: Boolean = false,
) {
    /** The list a filter leaves, by name, version or license. */
    val shown: List<LicenseEntry>
        get() = if (query.isBlank()) {
            licenses
        } else {
            licenses.filter {
                it.name.contains(query.trim(), ignoreCase = true) ||
                    it.license.contains(query.trim(), ignoreCase = true)
            }
        }

    val current: LicenseEntry? get() = shown.getOrNull(selected)

    /** The text a bug report needs: Graft, git, JDK and OS. */
    fun versionInfo(unknown: String): String = listOf(
        "Graft $version",
        "Git ${git ?: unknown}",
        "JDK ${system.jdk}",
        "OS ${system.os}",
    ).joinToString("\n")
}

internal sealed interface AboutEvent {
    data object Opened : AboutEvent

    data class QueryTyped(val query: String) : AboutEvent

    data class Moved(val by: Int) : AboutEvent

    data class Selected(val index: Int) : AboutEvent

    data object CopyVersionInfo : AboutEvent
}

internal sealed interface AboutAction {
    data object Start : AboutAction

    class Local(val change: (AboutState) -> AboutState) : AboutAction

    data object Copy : AboutAction
}

internal sealed interface AboutEffect {
    data class Copy(val text: String) : AboutEffect
}

/** About (S40): the version, the machine, git, and the third-party license list. */
internal class AboutViewModel(
    private val install: GitInstallRepository,
    private val licenses: LicenseSource,
    private val build: BuildVersion,
    private val system: SystemInfo = SystemInfo.current(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : EventActionViewModel<AboutState, AboutEvent, AboutAction, AboutEffect>(AboutState()) {
    private var opened = false

    override fun mapEventToAction(event: AboutEvent): AboutAction? = when (event) {
        AboutEvent.Opened -> if (opened) null else AboutAction.Start.also { opened = true }

        is AboutEvent.QueryTyped -> AboutAction.Local { it.copy(query = event.query, selected = 0) }

        is AboutEvent.Moved -> AboutAction.Local { s ->
            s.copy(selected = (s.selected + event.by).coerceIn(0, (s.shown.size - 1).coerceAtLeast(0)))
        }

        is AboutEvent.Selected -> AboutAction.Local { it.copy(selected = event.index) }

        AboutEvent.CopyVersionInfo -> AboutAction.Copy
    }

    override suspend fun onAction(action: AboutAction) {
        when (action) {
            AboutAction.Start -> {
                // Locals: inside `copy`, `system` and `build` would be the state's own properties.
                val machine = system
                val version = build.version()
                setState { copy(version = version, system = machine) }
                val git = (install.check() as? GitInstallStatus.Ready)?.install?.version
                // The report is a resource read and a JSON parse: off the UI thread.
                val list = withContext(io) { licenses.load() }
                setState { copy(git = git, licenses = list, loaded = true) }
            }

            is AboutAction.Local -> setState(action.change)

            AboutAction.Copy -> emitEffect(AboutEffect.Copy(currentState.versionInfo("not found")))
        }
    }
}
