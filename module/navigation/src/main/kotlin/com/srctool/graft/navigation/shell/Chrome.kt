// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.button.CamoButton
import com.srctool.camouflage.core.icon.CamoIcon
import com.srctool.camouflage.core.iconbutton.CamoIconButton
import com.srctool.camouflage.core.menu.CamoMenuAnchor
import com.srctool.camouflage.core.menu.CamoMenuItem
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.text.CamoText
import com.srctool.camouflage.core.theme.Appearance
import com.srctool.graft.core.ui.keys.shortcutText
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.core.ui.theme.GraftIcon
import com.srctool.graft.core.ui.theme.graftIcon

/** A repository tab the repository chip offers. */
internal data class OpenRepositoryTab(val id: String, val name: String)

/** What the toolbar shows and does; the window supplies each action. */
internal data class ToolbarActions(
    val onHome: () -> Unit,
    val onPick: (String) -> Unit,
    val onBranch: () -> Unit,
    val onSearch: () -> Unit,
    val onSettings: () -> Unit,
    val onWelcome: () -> Unit,
    val onAbout: () -> Unit,
    val onFocusMode: () -> Unit,
    val onGitLog: () -> Unit,
)

/**
 * The toolbar (Task 20 wireframe): Home, the repository chip, the branch chip, Search and the menu. On macOS it sits in
 * the transparent title bar, so [leadingInset] leaves room for the traffic lights. Pull, Push and the rest come with
 * the milestones that need them.
 */
@Composable
internal fun Toolbar(
    repositories: List<OpenRepositoryTab>,
    repository: String?,
    branch: String?,
    actions: ToolbarActions,
    leadingInset: Dp = 0.dp,
) {
    val colors = Camouflage.colors
    val density = Graft.tokens.density
    val none = graftText("toolbar.noRepository", "No repository")
    val toolbarLabel = graftText("toolbar", "Toolbar")
    Row(
        Modifier
            .fillMaxWidth()
            .height(density.formControl + 8.dp)
            .background(colors.surfaceContainerLow)
            .semantics { contentDescription = toolbarLabel }
            .padding(start = leadingInset + 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CamoIconButton(
            icon = { CamoIcon(graftIcon(GraftIcon.HOME), contentDescription = null, Modifier.size(density.icon)) },
            contentDescription = graftText("toolbar.home", "Home"),
            onClick = actions.onHome,
            modifier = Modifier.size(density.control),
            appearance = Appearance.Ghost,
            tooltip = graftText("toolbar.home", "Home") + "  " + shortcutText("Mod+T"),
        )
        val repositoryLabel = graftText("toolbar.repository", "Repository")
        CamoMenuAnchor(
            items =
            repositories.map { tab -> CamoMenuItem(tab.name, onClick = { actions.onPick(tab.id) }) } +
                if (repositories.isEmpty()) {
                    listOf(CamoMenuItem(none, onClick = {}, enabled = false))
                } else {
                    emptyList()
                },
        ) { open ->
            CamoButton(
                (repository ?: none) + "  ▾",
                onClick = open,
                modifier = Modifier.height(density.control).semantics {
                    contentDescription =
                        "$repositoryLabel, ${repository ?: none}"
                },
                appearance = Appearance.Ghost,
            )
        }
        val branchLabel = graftText("toolbar.branch", "Branch")
        CamoButton(
            (branch ?: "—") + "  ▾",
            onClick = actions.onBranch,
            modifier = Modifier.height(density.control).semantics {
                contentDescription =
                    "$branchLabel, ${branch ?: none}"
            },
            appearance = Appearance.Ghost,
            enabled = branch != null,
        )
        Spacer(Modifier.weight(1f))
        CamoButton(
            graftText("toolbar.search", "Search everywhere"),
            onClick = actions.onSearch,
            modifier = Modifier.height(density.control),
            appearance = Appearance.Outline,
        )
        val menu =
            listOf(
                CamoMenuItem(graftText("home.menu.settings", "Settings…"), onClick = actions.onSettings),
                CamoMenuItem(graftText("home.menu.welcome", "Welcome…"), onClick = actions.onWelcome),
                CamoMenuItem(graftText("toolbar.focus", "Focus mode"), onClick = actions.onFocusMode),
                CamoMenuItem(graftText("toolbar.gitlog", "Git log"), onClick = actions.onGitLog),
                CamoMenuItem(graftText("home.menu.about", "About Graft"), onClick = actions.onAbout),
            )
        val menuLabel = graftText("toolbar.menu", "Main menu")
        CamoMenuAnchor(items = menu) { open ->
            CamoIconButton(
                icon = {
                    CamoIcon(
                        graftIcon(GraftIcon.OVERFLOW_MENU),
                        contentDescription = null,
                        modifier = Modifier.size(density.icon),
                    )
                },
                contentDescription = menuLabel,
                onClick = open,
                modifier = Modifier.size(density.control),
                appearance = Appearance.Ghost,
                tooltip = menuLabel,
            )
        }
    }
}

/**
 * The status bar: the branch and how far it is from its upstream, and what's going on. A polite live region, so a
 * screen reader hears "Reading" and its end without the focus moving.
 */
@Composable
internal fun StatusBar(status: StatusLine, notice: String?, onDismissNotice: () -> Unit) {
    val colors = Camouflage.colors
    val tokens = Graft.tokens
    val label = graftText("status.label", "Status")
    val reading = graftText("status.reading", "Reading the repository…")
    val polling = graftText("status.polling", "Checking for changes every few seconds")
    val failed = graftText("status.failed", "Some of the repository couldn't be read")
    val gone = graftText("status.upstreamGone", "upstream gone")
    val detached = graftText("status.detached", "detached")
    val summary =
        listOfNotNull(
            status.branch?.let { branch ->
                buildString {
                    append(branch)
                    if (status.detached) append(" ($detached)")
                    if (status.ahead > 0) append("  ↑${status.ahead}")
                    if (status.behind > 0) append("  ↓${status.behind}")
                    if (status.upstreamGone) append("  ($gone)")
                }
            },
            reading.takeIf { status.reading && status.branch != null },
            polling.takeIf { status.polling },
            failed.takeIf { status.failed },
            notice,
        )
    Row(
        Modifier
            .fillMaxWidth()
            .height(tokens.density.row + 2.dp)
            .background(colors.surfaceContainerLow)
            .semantics {
                contentDescription = "$label: ${summary.joinToString(". ")}"
                liveRegion = LiveRegionMode.Polite
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status.branch != null) {
            CamoIcon(
                graftIcon(GraftIcon.BRANCH),
                contentDescription = null,
                modifier = Modifier.size(tokens.density.icon),
                tint = colors.onSurfaceVariant,
            )
            CamoText(
                summary.first(),
                modifier = Modifier.padding(start = 4.dp),
                style = tokens.dense,
                color = colors.onSurface,
            )
        }
        if (status.reading && status.branch != null) StatusNote("◌  $reading", colors.onSurfaceVariant)
        if (status.polling) StatusNote("·  $polling", colors.onSurfaceVariant)
        if (status.failed) StatusNote("·  $failed", colors.error)
        Spacer(Modifier.weight(1f))
        if (notice != null) {
            CamoText(notice, style = tokens.dense, color = colors.onSurface)
            CamoButton(
                graftText("status.dismiss", "Dismiss"),
                onClick = onDismissNotice,
                modifier = Modifier.padding(start = 8.dp).height(tokens.density.row),
                appearance = Appearance.Ghost,
            )
        }
    }
}

@Composable
private fun StatusNote(text: String, color: androidx.compose.ui.graphics.Color) {
    CamoText(text, modifier = Modifier.padding(start = 12.dp), style = Graft.tokens.dense, color = color)
}
