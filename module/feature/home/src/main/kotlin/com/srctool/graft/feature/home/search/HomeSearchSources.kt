// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.search

import com.srctool.graft.core.ui.keys.shortcutText
import com.srctool.graft.core.ui.l10n.GraftLocalizer
import com.srctool.graft.data.app.api.repositories.KnownRepositoryRepository
import com.srctool.graft.presentation.search.SearchCandidate
import com.srctool.graft.presentation.search.SearchGroup
import com.srctool.graft.presentation.search.SearchKind
import com.srctool.graft.presentation.search.SearchScope
import com.srctool.graft.presentation.search.SearchSource
import com.srctool.graft.presentation.search.SearchTarget
import com.srctool.sinew.models.Result
import java.nio.file.Path
import java.nio.file.Paths

/** The repositories Home lists, for Search Everywhere: by name and path; before typing, the last five opened. */
internal class RepositorySearchSource(
    private val known: KnownRepositoryRepository,
    private val home: Path = Paths.get(System.getProperty("user.home")),
) : SearchSource {
    override val group: SearchGroup = SearchGroup.Repositories

    override suspend fun candidates(query: String, scope: SearchScope): List<SearchCandidate> {
        val saved =
            when (val r = known.list()) {
                is Result.Success -> r.data.value
                is Result.Failure -> throw r.error
            }
        val live = saved.filter { !it.missing }
        val chosen = if (query.isBlank()) live.sortedByDescending { it.lastOpened }.take(RECENT) else live
        return chosen.map {
            SearchCandidate(
                key = "repository:${it.path}",
                kind = SearchKind.Repository,
                title = it.path.fileName?.toString() ?: it.path.toString(),
                target = SearchTarget.OpenRepository(it.path),
                detail = tilde(it.path),
                extra = it.path.toString(),
                recency = it.lastOpened,
            )
        }
    }

    /** `~/Projects/graft` for a path under the user's home, so the list reads the way the user writes paths. */
    private fun tilde(path: Path): String =
        if (path.startsWith(home) && path != home) "~/" + home.relativize(path).joinToString("/") else path.toString()

    private companion object {
        const val RECENT = 5
    }
}

/** Home's commands, by the ids the host runs them under ([HomeActions]). */
internal class HomeActionSearchSource(private val l10n: GraftLocalizer = GraftLocalizer()) : SearchSource {
    override val group: SearchGroup = SearchGroup.Actions

    override suspend fun candidates(query: String, scope: SearchScope): List<SearchCandidate> {
        val all =
            listOf(
                action(HomeActions.OPEN, "home.action.open", "Open repository…", "Mod+O"),
                action(HomeActions.CLONE, "home.action.clone", "Clone repository…", "Mod+Shift+O"),
                action(HomeActions.INIT, "home.action.init", "Init repository…", null),
                action(HomeActions.SCAN, "home.action.scan", "Scan a folder for repositories…", null),
                action(HomeActions.NEW_WORKSPACE, "home.action.newWorkspace", "New workspace…", null),
            )
        return if (query.isBlank()) all.filter { it.shortcut != null } else all
    }

    private fun action(id: String, key: String, fallback: String, shortcut: String?) = SearchCandidate(
        key = "action:$id",
        kind = SearchKind.Action,
        title = l10n.resolve(key, emptyMap()) ?: fallback,
        target = SearchTarget.RunAction(id),
        shortcut = shortcut?.let(::shortcutText),
    )
}

/** The ids of the actions Home contributes; the host turns each into the [HomeCommand] Home carries out. */
public object HomeActions {
    public const val OPEN: String = "home.open"
    public const val CLONE: String = "home.clone"
    public const val INIT: String = "home.init"
    public const val SCAN: String = "home.scan"
    public const val NEW_WORKSPACE: String = "home.newWorkspace"

    /** The command for an action id of Home's, or null for anyone else's. */
    public fun command(id: String): HomeCommand? = when (id) {
        OPEN -> HomeCommand.Open
        CLONE -> HomeCommand.Clone
        INIT -> HomeCommand.Init
        SCAN -> HomeCommand.Scan
        NEW_WORKSPACE -> HomeCommand.NewWorkspace
        else -> null
    }
}

/** Something Home does when another part of the window asks, such as an action chosen in Search Everywhere. */
public enum class HomeCommand { Open, Clone, Init, Scan, NewWorkspace }
