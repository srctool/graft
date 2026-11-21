// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.gitlog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.text.CamoText
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.data.git.api.models.GitLogEntry
import com.srctool.sinew.viewmodel.ObserveEffects
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun GitLogPage(viewModel: GitLogViewModel) {
    val state by viewModel.state.collectAsState()

    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(viewModel) { viewModel.onEvent(GitLogEvent.Opened) }
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is GitLogEffect.CopyToClipboard -> clipboard.setText(AnnotatedString(effect.text))
        }
    }
    GitLogContent(state, viewModel::onEvent)
}

/** The log without its view model, for screenshot tests. */
@Composable
internal fun GitLogContent(state: GitLogState, onEvent: (GitLogEvent) -> Unit) {
    val colors = Camouflage.colors
    val shown = state.shown
    val list = rememberLazyListState()
    // Newest at the bottom, like a console: follow it until the user picks a row.
    LaunchedEffect(shown.size) {
        if (state.selected < 0 && shown.isNotEmpty()) list.scrollToItem(shown.lastIndex)
    }
    val label = graftText("gitlog.title", "Git log")
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.surfaceContainerLowest)
            .semantics { contentDescription = label }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> onEvent(GitLogEvent.Moved(1))

                    Key.DirectionUp -> onEvent(GitLogEvent.Moved(-1))

                    Key.C -> if (state.selected >=
                        0
                    ) {
                        onEvent(GitLogEvent.CopyCommand)
                    } else {
                        return@onPreviewKeyEvent false
                    }

                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        Row(Modifier.fillMaxWidth().height(Graft.tokens.density.row).padding(horizontal = 8.dp)) {
            Row(
                Modifier.clickable { onEvent(GitLogEvent.FailedOnly(!state.failedOnly)) }.semantics {
                    selected = state.failedOnly
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CamoText(
                    if (state.failedOnly) {
                        graftText("gitlog.all", "Show all commands")
                    } else {
                        graftText("gitlog.failed", "Show only failures")
                    },
                    style = Graft.tokens.dense,
                    color = colors.primary,
                )
            }
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CamoText(
                    if (state.failedOnly) {
                        graftText("gitlog.nofailures", "No command has failed.")
                    } else {
                        graftText("gitlog.empty", "No git command has run yet.")
                    },
                    style = Graft.tokens.dense,
                    color = colors.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                itemsIndexed(shown) { index, entry ->
                    Row(entry, index == state.selected) { onEvent(GitLogEvent.Selected(index)) }
                }
            }
        }
    }
}

private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

@Composable
private fun Row(entry: GitLogEntry, selected: Boolean, onClick: () -> Unit) {
    val colors = Camouflage.colors
    val time = timeFormat.format(Instant.ofEpochMilli(entry.startedAtMillis))
    val outcome =
        when (entry.exitCode) {
            0 -> graftText("gitlog.ok", "succeeded")
            null -> graftText("gitlog.never", "did not run")
            else -> graftText("gitlog.failedwith", "failed, exit code {0}").replace("{0}", entry.exitCode.toString())
        }
    Row(
        Modifier
            .fillMaxWidth()
            .height(Graft.tokens.density.row)
            .background(if (selected) colors.primaryContainer else colors.surfaceContainerLowest)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                contentDescription = "$time, git ${entry.command}, $outcome, ${entry.durationMillis} ms"
            }.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CamoText(time, style = Graft.tokens.dense, color = colors.onSurfaceVariant)
        CamoText(
            "git ${entry.command}",
            modifier = Modifier.padding(start = 12.dp).weight(1f),
            style = Graft.tokens.dense.copy(fontFamily = FontFamily.Monospace),
            color = if (entry.failed) colors.error else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        CamoText(
            "${entry.durationMillis} ms",
            modifier = Modifier.width(72.dp).padding(start = 8.dp),
            style = Graft.tokens.dense,
            color = colors.onSurfaceVariant,
        )
        CamoText(
            when (entry.exitCode) {
                0 -> "✓"
                null -> "–"
                else -> "✗ ${entry.exitCode}"
            },
            modifier = Modifier.width(48.dp),
            style = Graft.tokens.dense,
            color = if (entry.failed) colors.error else colors.onSurfaceVariant,
        )
    }
}
