// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.button.CamoButton
import com.srctool.camouflage.core.menu.CamoMenuAnchor
import com.srctool.camouflage.core.menu.CamoMenuGroup
import com.srctool.camouflage.core.menu.CamoMenuItem
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.text.CamoText
import com.srctool.camouflage.core.theme.Appearance
import com.srctool.graft.core.ui.theme.Graft

/**
 * A label and a drop-down of exclusive [options] (value to name), as Onboarding's look step and Settings use them.
 * Screen readers hear "label, current choice".
 */
@Composable
public fun <T> ChoiceRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onChoose: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    labelWidth: Dp = 140.dp,
) {
    val current = options.firstOrNull { it.first == selected }?.second.orEmpty()
    val items =
        listOf(
            CamoMenuGroup(
                options.map { (value, name) ->
                    CamoMenuItem(
                        name,
                        onClick = { onChoose(value) },
                        checked =
                        value == selected,
                    )
                },
                exclusive = true,
            ),
        )
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = "$label, $current" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CamoText(
            label,
            modifier = Modifier.width(labelWidth),
            style = Graft.tokens.dense,
            color = Camouflage.colors.onSurfaceVariant,
        )
        CamoMenuAnchor(items = items) { open ->
            CamoButton(
                "$current  ▾",
                onClick = open,
                modifier = Modifier.height(Graft.tokens.density.formControl),
                appearance = Appearance.Outline,
                enabled = enabled,
            )
        }
    }
}
