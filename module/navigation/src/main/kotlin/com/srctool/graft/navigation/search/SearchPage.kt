// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.search

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.dialog.CamoDialogFrame
import com.srctool.graft.core.ui.components.SearchPalette
import com.srctool.graft.core.ui.components.SearchPaletteRow
import com.srctool.graft.core.ui.components.SearchPaletteSection
import com.srctool.graft.core.ui.components.SearchPaletteStatus
import com.srctool.graft.core.ui.components.SearchPaletteTab
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.navigation.rememberDialogOwner
import com.srctool.graft.presentation.search.SearchGroup
import com.srctool.graft.presentation.search.SearchKind
import com.srctool.graft.presentation.search.SearchScope
import com.srctool.graft.presentation.search.SearchTarget
import com.srctool.sinew.exception.displayMessage
import com.srctool.sinew.models.SinewException
import com.srctool.sinew.viewmodel.ObserveEffects
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Search Everywhere (S39) over the window; [onActivate] carries out what Enter chose, and the popup closes itself. */
@Composable
internal fun SearchDialog(
    scope: SearchScope,
    startTab: SearchTab,
    onActivate: (SearchTarget) -> Unit,
    onClose: () -> Unit,
) {
    val viewModel =
        koinViewModel<SearchViewModel>(viewModelStoreOwner = rememberDialogOwner("search")) { parametersOf(scope) }
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) {
        viewModel.onEvent(SearchEvent.Opened)
        // Find Action opens on the Actions tab.
        if (startTab != SearchTab.All) viewModel.onEvent(SearchEvent.TabChosen(startTab))
    }
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is SearchEffect.Activate -> {
                onClose()
                onActivate(effect.target)
            }

            SearchEffect.Close -> onClose()
        }
    }
    CamoDialogFrame(onDismissRequest = onClose) { SearchContent(state, viewModel::onEvent) }
}

/** The popup's contents without the view model or frame, for screenshot tests. */
@Composable
internal fun SearchContent(state: SearchState, onEvent: (SearchEvent) -> Unit, modifier: Modifier = Modifier) {
    val groupNames = groupNames()
    val tabNames = mapOf(SearchTab.All to graftText("search.tab.all", "All")) +
        SearchTab.entries.filter { it.group != null }.associateWith { groupNames.getValue(it.group!!) }
    val kinds = kindNames()
    val retry = graftText("search.retry", "Retry")
    val tabs =
        SearchTab.entries.map { tab ->
            SearchPaletteTab(
                id = tab.name,
                label = tabNames.getValue(tab),
                selected = tab == state.tab,
                spoken = graftText(
                    "search.tab.label",
                    "{name}, {count} results",
                    "name" to tabNames.getValue(tab),
                    "count" to state.count(tab),
                ),
            )
        }
    val shown = state.shown
    val sections =
        SearchGroup.entries.filter { state.tab.group == null || it == state.tab.group }.map { group ->
            val rows =
                shown.filter { it.group == group }.map { s ->
                    val c = s.ranked.candidate
                    SearchPaletteRow(
                        key = c.key,
                        title = c.title,
                        marks = s.ranked.marks,
                        detail = c.detail,
                        trailing = c.shortcut,
                        spoken = listOfNotNull(
                            kinds.getValue(c.kind),
                            c.title,
                            c.detail,
                            c.shortcut,
                        ).joinToString(", "),
                    )
                }
            val groupState = state.groups[group]
            SearchPaletteSection(
                title = groupNames.getValue(group),
                rows = rows,
                status =
                when (groupState) {
                    GroupState.Loading -> SearchPaletteStatus.Loading

                    is GroupState.Failed -> SearchPaletteStatus.Failed(failureText(groupState.error), retry) {
                        onEvent(SearchEvent.Retried(group))
                    }

                    else -> SearchPaletteStatus.Ready
                },
                note = (groupState as? GroupState.Ready)?.note,
            )
        }
    val nothing = state.query.isNotBlank() && !state.searching && state.total == 0 &&
        state.groups.values.none { it is GroupState.Failed }
    SearchPalette(
        query = state.query,
        onQueryChange = { onEvent(SearchEvent.QueryTyped(it)) },
        placeholder = graftText("search.placeholder", "Search everywhere…"),
        boxLabel = graftText("search.box", "Search everywhere"),
        tabs = tabs,
        onTab = { id -> onEvent(SearchEvent.TabChosen(SearchTab.valueOf(id))) },
        onTabMove = { onEvent(SearchEvent.TabMoved(it)) },
        sections = sections,
        selected = state.selected,
        onSelect = {
            onEvent(SearchEvent.Selected(it))
            onEvent(SearchEvent.Activated)
        },
        onMove = { onEvent(SearchEvent.Moved(it)) },
        onActivate = { onEvent(SearchEvent.Activated) },
        onEscape = { onEvent(SearchEvent.Escaped) },
        onClearText = { onEvent(SearchEvent.Cleared) },
        footer = graftText(
            "search.footer",
            "↑↓ move · Enter open · Tab next tab · Esc close · {shown} of {total} shown",
            "shown" to shown.size,
            "total" to state.total,
        ),
        emptyText = if (nothing) {
            graftText(
                "search.nothing",
                "Nothing matches \"{query}\".",
                "query" to state.query.trim(),
            )
        } else {
            null
        },
        hint = if (state.needsRepository) {
            graftText(
                "search.needsRepository",
                "Open a repository to search its branches, commits and files.",
            )
        } else {
            null
        },
        listLabel = graftText("search.list", "Results, {count}", "count" to shown.size),
        modifier = modifier.width(760.dp).height(520.dp).padding(12.dp),
    )
}

@Composable
private fun failureText(error: Throwable): String {
    val message = (error as? SinewException)?.displayMessage(Graft.localizer) ?: error.message.orEmpty()
    val code = (error as? SinewException)?.code
    return if (code != null && code !in message) "$message · $code" else message
}

@Composable
internal fun groupNames(): Map<SearchGroup, String> = mapOf(
    SearchGroup.Repositories to graftText("search.group.repositories", "Repositories"),
    SearchGroup.Refs to graftText("search.group.refs", "Branches and tags"),
    SearchGroup.Commits to graftText("search.group.commits", "Commits"),
    SearchGroup.Files to graftText("search.group.files", "Files"),
    SearchGroup.Actions to graftText("search.group.actions", "Actions"),
)

@Composable
private fun kindNames(): Map<SearchKind, String> = mapOf(
    SearchKind.Repository to graftText("search.kind.repository", "Repository"),
    SearchKind.Branch to graftText("search.kind.branch", "Branch"),
    SearchKind.Tag to graftText("search.kind.tag", "Tag"),
    SearchKind.Commit to graftText("search.kind.commit", "Commit"),
    SearchKind.File to graftText("search.kind.file", "File"),
    SearchKind.Action to graftText("search.kind.action", "Action"),
)
