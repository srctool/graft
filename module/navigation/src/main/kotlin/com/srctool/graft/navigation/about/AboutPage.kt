// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.about

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.button.CamoButton
import com.srctool.camouflage.core.dialog.CamoDialogFrame
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.search.CamoSearchField
import com.srctool.camouflage.core.text.CamoText
import com.srctool.camouflage.core.theme.Appearance
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.navigation.rememberDialogOwner
import com.srctool.sinew.viewmodel.ObserveEffects
import org.koin.compose.viewmodel.koinViewModel

private const val SOURCE_URL = "https://github.com/srctool/graft"
private const val GPL_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

/** About (S40) in its own dialog. */
@Composable
internal fun AboutDialog(onClose: () -> Unit) {
    val viewModel = koinViewModel<AboutViewModel>(viewModelStoreOwner = rememberDialogOwner("AboutViewModel"))
    val state by viewModel.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(viewModel) { viewModel.onEvent(AboutEvent.Opened) }
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is AboutEffect.Copy -> clipboard.setText(AnnotatedString(effect.text))
        }
    }
    CamoDialogFrame(onDismissRequest = onClose) { AboutContent(state, viewModel::onEvent, onClose) }
}

/** The dialog's contents without the view model or frame, for screenshot tests. */
@Composable
internal fun AboutContent(state: AboutState, onEvent: (AboutEvent) -> Unit, onClose: () -> Unit) {
    val colors = Camouflage.colors
    val uri = LocalUriHandler.current
    val list = rememberLazyListState()
    val shown = state.shown
    LaunchedEffect(state.selected) { if (shown.isNotEmpty()) list.animateScrollToItem(state.selected.coerceAtLeast(0)) }
    val listLabel = graftText("about.licenses.label", "Third-party licenses, {count}", "count" to shown.size)
    Column(
        Modifier.width(760.dp).height(540.dp).padding(16.dp).onPreviewKeyEvent { event ->
            event.type == KeyEventType.KeyDown && event.key == Key.Escape && true.also { onClose() }
        },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CamoText(
                    graftText("about.title", "About Graft") + "  " + state.version,
                    style = Graft.tokens.denseStrong,
                )
                CamoText(
                    graftText(
                        "about.system",
                        "Git {git} · JDK {jdk} · {os}",
                        "git" to (state.git ?: graftText("about.git.none", "not found")),
                        "jdk" to state.system.jdk,
                        "os" to state.system.os,
                    ),
                    style = Graft.tokens.dense,
                    color = colors.onSurfaceVariant,
                )
            }
            CamoButton(
                graftText("about.copy", "Copy version info"),
                onClick = { onEvent(AboutEvent.CopyVersionInfo) },
                modifier = Modifier.height(Graft.tokens.density.control),
                appearance = Appearance.Outline,
            )
            CamoButton(
                graftText("settings.close", "Close"),
                onClick = onClose,
                modifier = Modifier.height(Graft.tokens.density.control).padding(start = 8.dp),
                appearance = Appearance.Ghost,
            )
        }
        CamoText(
            graftText(
                "about.notice",
                "Graft is free software under the GNU General Public License, version 3 or later. " +
                    "Graft comes with ABSOLUTELY NO WARRANTY.",
            ),
            style = Graft.tokens.dense,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CamoButton(
                graftText("about.source", "Source code"),
                onClick = { uri.openUri(SOURCE_URL) },
                modifier = Modifier.height(Graft.tokens.density.control),
                appearance = Appearance.Link,
            )
            CamoButton(
                graftText("about.gpl", "GPL-3.0"),
                onClick = { uri.openUri(GPL_URL) },
                modifier = Modifier.height(Graft.tokens.density.control),
                appearance = Appearance.Link,
            )
        }
        CamoText(graftText("about.licenses", "Third-party licenses"), style = Graft.tokens.denseStrong)
        CamoSearchField(
            query = state.query,
            onQueryChange = { onEvent(AboutEvent.QueryTyped(it)) },
            onSearch = {},
            placeholder = graftText("about.filter", "Filter…"),
            modifier = Modifier.width(280.dp).height(Graft.tokens.density.control),
        )
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = listLabel }.focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> onEvent(AboutEvent.Moved(1))
                            Key.DirectionUp -> onEvent(AboutEvent.Moved(-1))
                            else -> return@onPreviewKeyEvent false
                        }
                        true
                    },
                state = list,
            ) {
                itemsIndexed(shown) { index, entry ->
                    val selected = index == state.selected
                    val spoken =
                        listOfNotNull(entry.name, entry.version, entry.license.ifBlank { null }).joinToString(", ")
                    Row(
                        Modifier.fillMaxWidth().height(Graft.tokens.density.row)
                            .background(if (selected) colors.surfaceContainerHigh else Color.Transparent)
                            .clickable { onEvent(AboutEvent.Selected(index)) }
                            .semantics {
                                this.selected = selected
                                contentDescription = spoken
                            }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CamoText(
                            entry.name,
                            Modifier.weight(2f),
                            style = Graft.tokens.dense,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        CamoText(
                            entry.version.orEmpty(),
                            Modifier.width(70.dp),
                            style = Graft.tokens.dense,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                        )
                        CamoText(
                            entry.license.ifBlank {
                                "—"
                            },
                            Modifier.weight(2f),
                            style = Graft.tokens.dense,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Box(Modifier.width(260.dp).fillMaxHeight().background(colors.surfaceContainerLow).padding(12.dp)) {
                val entry = state.current
                if (entry != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CamoText(
                            entry.license.ifBlank {
                                graftText("about.license.unknown", "License not stated")
                            },
                            style = Graft.tokens.denseStrong,
                        )
                        CamoText(
                            entry.name + (
                                entry.version?.let {
                                    " $it"
                                } ?: ""
                                ),
                            style = Graft.tokens.dense,
                            color = colors.onSurfaceVariant,
                        )
                        entry.url?.let { url ->
                            CamoButton(
                                graftText("about.license.read", "Read the license"),
                                onClick = { uri.openUri(url) },
                                modifier = Modifier.height(Graft.tokens.density.control),
                                appearance = Appearance.Link,
                            )
                        }
                    }
                }
            }
        }
    }
}
