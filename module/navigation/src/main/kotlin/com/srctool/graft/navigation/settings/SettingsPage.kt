// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.banner.CamoBanner
import com.srctool.camouflage.core.button.CamoButton
import com.srctool.camouflage.core.checkbox.CamoCheckbox
import com.srctool.camouflage.core.dialog.CamoDialogFrame
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.search.CamoSearchField
import com.srctool.camouflage.core.text.CamoText
import com.srctool.camouflage.core.textfield.CamoTextField
import com.srctool.camouflage.core.theme.Appearance
import com.srctool.camouflage.core.theme.Tone
import com.srctool.graft.core.ui.components.ChoiceRow
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.data.app.api.models.Density
import com.srctool.graft.data.app.api.models.DiffStyle
import com.srctool.graft.data.app.api.models.ThemeChoice
import com.srctool.graft.navigation.rememberDialogOwner
import com.srctool.sinew.exception.displayMessage
import com.srctool.sinew.models.SinewException
import com.srctool.sinew.viewmodel.ObserveEffects
import org.koin.compose.viewmodel.koinViewModel

/** Settings (S37) in its own dialog; [onWelcomeAgain] closes it and asks Home to open the welcome steps. */
@Composable
internal fun SettingsDialog(onClose: () -> Unit, onWelcomeAgain: () -> Unit) {
    val viewModel = koinViewModel<SettingsViewModel>(viewModelStoreOwner = rememberDialogOwner("SettingsViewModel"))
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) { viewModel.onEvent(SettingsEvent.Opened) }
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            SettingsEffect.WelcomeAgain -> onWelcomeAgain()
        }
    }
    CamoDialogFrame(onDismissRequest = onClose) { SettingsContent(state, viewModel::onEvent, onClose) }
}

/** The dialog's contents without the view model or frame, for screenshot tests. */
@Composable
internal fun SettingsContent(state: SettingsState, onEvent: (SettingsEvent) -> Unit, onClose: () -> Unit) {
    val colors = Camouflage.colors
    val labels = settingsLabels()
    val label: (String) -> String = { labels.getValue(it) }
    val pages = visiblePages(state.query, label)
    // A search can leave out the chosen page: the first page it left is shown instead; none left shows a message.
    val current = state.page.takeIf { it in pages } ?: pages.firstOrNull()
    val search = remember { FocusRequester() }
    val title = graftText("settings.title", "Settings")
    Column(
        Modifier.width(820.dp).height(520.dp).onPreviewKeyEvent { event ->
            val find =
                event.type == KeyEventType.KeyDown && event.key == Key.F && (event.isMetaPressed || event.isCtrlPressed)
            if (find) runCatching { search.requestFocus() }
            find || (event.type == KeyEventType.KeyDown && event.key == Key.Escape).also { if (it) onClose() }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CamoText(title, style = Graft.tokens.denseStrong, modifier = Modifier.weight(1f))
            CamoButton(
                graftText("settings.close", "Close"),
                onClick = onClose,
                modifier = Modifier.height(Graft.tokens.density.control),
                appearance = Appearance.Ghost,
            )
        }
        if (state.readOnly) {
            CamoBanner(
                graftText(
                    "settings.readOnly",
                    "These settings were saved by a newer Graft, so they're shown as they are and can't be changed here.",
                ),
                modifier = Modifier.fillMaxWidth(),
                tone = Tone.Warning,
            )
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.width(240.dp).fillMaxHeight().background(colors.surfaceContainerLow).padding(8.dp)) {
                CamoSearchField(
                    query = state.query,
                    onQueryChange = { onEvent(SettingsEvent.QueryTyped(it)) },
                    onSearch = {},
                    placeholder = graftText("settings.search", "Search settings…"),
                    modifier = Modifier.fillMaxWidth().height(Graft.tokens.density.control).focusRequester(search),
                )
                Spacer(Modifier.height(8.dp))
                Tree(state.copy(page = current ?: state.page), pages, label, onEvent)
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp)) {
                if (current != null) {
                    Page(state.copy(page = current), label, onEvent)
                } else {
                    CamoText(
                        graftText("settings.noMatch", "No settings match \"{query}\".", "query" to state.query.trim()),
                        style = Graft.tokens.dense,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Tree(
    state: SettingsState,
    pages: List<SettingsPage>,
    label: (String) -> String,
    onEvent: (SettingsEvent) -> Unit,
) {
    val colors = Camouflage.colors
    val treeLabel = graftText("settings.tree", "Settings, tree")
    Column(
        Modifier.fillMaxWidth().semantics { contentDescription = treeLabel }.focusable().onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || pages.isEmpty()) return@onPreviewKeyEvent false
            val at = pages.indexOf(state.page).coerceAtLeast(0)
            val next =
                when (event.key) {
                    Key.DirectionDown -> pages.getOrNull(at + 1)
                    Key.DirectionUp -> pages.getOrNull(at - 1)
                    else -> return@onPreviewKeyEvent false
                }
            next?.let { onEvent(SettingsEvent.PageChosen(it)) }
            true
        },
    ) {
        for (group in SettingsGroup.entries) {
            val inGroup = pages.filter { it.group == group }
            if (inGroup.isEmpty()) continue
            CamoText(
                label(group.titleKey),
                style = Graft.tokens.denseStrong,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
            )
            for (page in inGroup) {
                val selected = page == state.page
                Box(
                    Modifier.fillMaxWidth().height(Graft.tokens.density.row)
                        .background(if (selected) colors.surfaceContainerHigh else Color.Transparent)
                        .clickable { onEvent(SettingsEvent.PageChosen(page)) }
                        .semantics {
                            this.selected = selected
                            contentDescription = label(page.titleKey)
                        }
                        .padding(start = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    CamoText(label(page.titleKey), style = Graft.tokens.dense)
                }
            }
        }
    }
}

@Composable
private fun Page(state: SettingsState, label: (String) -> String, onEvent: (SettingsEvent) -> Unit) {
    val colors = Camouflage.colors
    val enabled = state.loaded && !state.readOnly
    val scope = graftText("settings.scope.app", "app")
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CamoText(label(state.page.titleKey), style = Graft.tokens.denseStrong)
        @Composable
        fun Line(setting: SettingRow, content: @Composable () -> Unit) {
            val match = state.query.isNotBlank() && rowMatches(setting, state.query, label)
            Row(
                Modifier.fillMaxWidth().then(
                    if (match) Modifier.background(colors.primary.copy(alpha = 0.12f)) else Modifier,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { content() }
                if (setting != SettingRow.Welcome) {
                    CamoText(scope, style = Graft.tokens.dense, color = colors.onSurfaceVariant)
                }
            }
        }
        when (state.page) {
            SettingsPage.Appearance -> {
                Line(SettingRow.Theme) {
                    ChoiceRow(
                        label("settings.theme"),
                        listOf(
                            ThemeChoice.SYSTEM to graftText("settings.theme.system", "System"),
                            ThemeChoice.LIGHT to graftText("settings.theme.light", "Light"),
                            ThemeChoice.DARK to graftText("settings.theme.dark", "Dark"),
                        ),
                        state.settings.theme,
                        { onEvent(SettingsEvent.ThemeChosen(it)) },
                        enabled = enabled,
                    )
                }
                Line(SettingRow.Density) {
                    ChoiceRow(
                        label("settings.density"),
                        listOf(
                            Density.STANDARD to graftText("settings.density.standard", "Standard"),
                            Density.COMFORTABLE to graftText("settings.density.comfortable", "Comfortable"),
                        ),
                        state.settings.density,
                        { onEvent(SettingsEvent.DensityChosen(it)) },
                        enabled = enabled,
                    )
                }
                CamoText(
                    graftText(
                        "settings.appearance.more",
                        "Language, keymap and avatars arrive with the features that use them.",
                    ),
                    style = Graft.tokens.dense,
                    color = colors.onSurfaceVariant,
                )
            }

            SettingsPage.Diffs -> {
                Line(SettingRow.DiffStyle) {
                    ChoiceRow(
                        label("settings.diffStyle"),
                        listOf(
                            DiffStyle.UNIFIED to graftText("settings.diffStyle.unified", "Unified"),
                            DiffStyle.SIDE_BY_SIDE to graftText("settings.diffStyle.sideBySide", "Side by side"),
                        ),
                        state.settings.diffStyle,
                        { onEvent(SettingsEvent.DiffStyleChosen(it)) },
                        enabled = enabled,
                    )
                }
                Line(SettingRow.IgnoreWhitespace) {
                    CamoCheckbox(
                        state.settings.ignoreWhitespace,
                        { onEvent(SettingsEvent.IgnoreWhitespaceToggled) },
                        label = label("settings.ignoreWhitespace"),
                        enabled = enabled,
                    )
                }
                Line(SettingRow.InlineLimit) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                        CamoTextField(
                            state.limitText,
                            { onEvent(SettingsEvent.LimitTyped(it)) },
                            modifier = Modifier.width(220.dp),
                            label = label("settings.inlineLimit"),
                            error = if (state.limitInvalid) {
                                graftText(
                                    "settings.inlineLimit.bad",
                                    "Enter a whole number of MB, 1 or more.",
                                )
                            } else {
                                null
                            },
                            enabled = enabled,
                        )
                        CamoText(
                            graftText("settings.inlineLimit.unit", "MB. Bigger files aren't diffed inline."),
                            style = Graft.tokens.dense,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
            }

            SettingsPage.Onboarding -> Line(SettingRow.Welcome) {
                CamoButton(
                    label("settings.welcome"),
                    onClick = { onEvent(SettingsEvent.WelcomeAgain) },
                    modifier = Modifier.height(Graft.tokens.density.control),
                    appearance = Appearance.Outline,
                )
            }
        }
        state.error?.let {
            val message = (it as? SinewException)?.displayMessage(Graft.localizer) ?: it.message.orEmpty()
            val code = (it as? SinewException)?.code
            CamoText(
                if (code != null && code !in message) "$message · $code" else message,
                style = Graft.tokens.dense,
                color = colors.error,
            )
        }
    }
}
