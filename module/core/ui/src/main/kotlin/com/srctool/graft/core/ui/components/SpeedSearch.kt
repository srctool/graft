// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.icon.CamoIcon
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.text.CamoText
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.core.ui.theme.GraftIcon
import com.srctool.graft.core.ui.theme.graftIcon

/**
 * How typed text matches a label (camouflage#73), case-insensitively: as a prefix, anywhere in it, or as camel humps,
 * each typed character continuing the current run or starting a new word (`fLog` matches `featureLogin`,
 * `feature/login`, `fix-log`). Returns the matched ranges for highlighting, or null when it doesn't match.
 */
public object SpeedSearchMatcher {
    public fun match(label: String, query: String): List<IntRange>? {
        if (query.isEmpty()) return emptyList()
        val at = label.indexOf(query, ignoreCase = true)
        if (at >= 0) return listOf(at until at + query.length)
        return humps(label, query)
    }

    private fun humps(label: String, query: String): List<IntRange>? {
        val ranges = ArrayList<IntRange>()
        var start = -1
        var li = 0
        for (ch in query) {
            // Continue the run if the next label character matches; otherwise jump to the next word start.
            if (start >= 0 && li < label.length && label[li].equals(ch, ignoreCase = true)) {
                li++
                continue
            }
            if (start >= 0) ranges.add(start until li)
            var next = li
            while (next < label.length &&
                !(isWordStart(label, next) && label[next].equals(ch, ignoreCase = true))
            ) {
                next++
            }
            if (next >= label.length) return null
            start = next
            li = next + 1
        }
        if (start >= 0) ranges.add(start until li)
        return ranges
    }

    private fun isWordStart(label: String, i: Int): Boolean {
        if (i == 0) return true
        val previous = label[i - 1]
        val camelHump = label[i].isUpperCase() && !previous.isUpperCase()
        return camelHump || previous in "/-_. :" || previous.isDigit() != label[i].isDigit()
    }
}

/** Whether speed search hides what doesn't match or only moves the selection to what does (camouflage#73). */
public enum class SpeedSearchMode { FILTER, JUMP }

/**
 * The text typed into a focused list, tree or table (Keyboard Shortcuts: "typing in a focused list or tree filters
 * it; Esc clears"). Owned by the list's screen, which asks [matches] per item label.
 */
@Stable
public class SpeedSearchState(public val mode: SpeedSearchMode = SpeedSearchMode.FILTER) {
    public var query: String by mutableStateOf("")
        private set

    public val active: Boolean get() = query.isNotEmpty()

    /** The matched ranges of [label], or null when it doesn't match; every label matches while nothing is typed. */
    public fun matches(label: String): List<IntRange>? = SpeedSearchMatcher.match(label, query)

    public fun type(text: String) {
        query += text
    }

    public fun backspace() {
        query = query.dropLast(1)
    }

    public fun clear() {
        query = ""
    }

    /**
     * Takes [event] if it edits the query: a printable character without Ctrl, Cmd or Alt, Backspace while typing, Esc
     * while typing. Everything else (arrows, Enter, shortcuts) stays with the list.
     */
    public fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        when (event.key) {
            Key.Escape -> return active.also { if (it) clear() }
            Key.Backspace -> return active.also { if (it) backspace() }
            else -> Unit
        }
        if (event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) return false
        val code = event.utf16CodePoint
        if (code < 0x20 || code == 0x7F || Character.isISOControl(code)) return false
        // A space only continues a query; on its own it stays the list's (toggles, opens).
        if (code == ' '.code && !active) return false
        type(String(Character.toChars(code)))
        return true
    }
}

/** Sends typed text to [state] before the list sees it. */
public fun Modifier.speedSearch(state: SpeedSearchState): Modifier = onPreviewKeyEvent(state::onKey)

/** [label] with [ranges] drawn in the theme's match style: bold, on the accent's container color. */
@Composable
public fun highlightMatches(label: String, ranges: List<IntRange>?): AnnotatedString {
    val colors = Camouflage.colors
    val style =
        SpanStyle(
            fontWeight = FontWeight.SemiBold,
            background = colors.primaryContainer,
            color = colors.onPrimaryContainer,
        )
    return buildAnnotatedString {
        append(label)
        ranges.orEmpty().forEach { r -> addStyle(style, r.first, r.last + 1) }
    }
}

/** A node of a tree filtered by speed search, with the ranges its label matched (null for a kept ancestor). */
public data class FilteredNode<T>(val node: T, val matches: List<IntRange>?, val children: List<FilteredNode<T>>)

/**
 * [roots] filtered by [query]: nodes whose label matches, and the ancestors of those, so a match is never shown out of
 * its place; a matching node keeps all its children (camouflage#73, "keep the ancestors of matching nodes").
 */
public fun <T> filterTree(
    roots: List<T>,
    children: (T) -> List<T>,
    label: (T) -> String,
    query: String,
): List<FilteredNode<T>> {
    fun all(node: T): FilteredNode<T> =
        FilteredNode(node, SpeedSearchMatcher.match(label(node), query), children(node).map(::all))

    fun keep(node: T): FilteredNode<T>? {
        val matched = SpeedSearchMatcher.match(label(node), query)
        if (matched != null) return FilteredNode(node, matched, children(node).map(::all))
        val kept = children(node).mapNotNull(::keep)
        return if (kept.isEmpty()) null else FilteredNode(node, null, kept)
    }
    if (query.isEmpty()) return roots.map(::all)
    return roots.mapNotNull(::keep)
}

/**
 * The typed text, shown over the list's top edge while speed search is active, with the number of matches for screen
 * readers; it turns to the error color when nothing matches.
 */
@Composable
public fun SpeedSearchField(state: SpeedSearchState, matchCount: Int, modifier: Modifier = Modifier) {
    if (!state.active) return
    val colors = Camouflage.colors
    val none = matchCount == 0
    val noMatches = graftText("ui.speedSearch.noMatches", "No matches")
    Row(
        modifier
            .height(Graft.tokens.density.control)
            .background(colors.surfaceContainerHighest)
            .border(1.dp, if (none) colors.error else colors.primary)
            .padding(horizontal = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CamoIcon(
            graftIcon(GraftIcon.SEARCH),
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = colors.onSurfaceVariant,
        )
        CamoText(
            state.query,
            modifier = Modifier.padding(start = 4.dp),
            style = Graft.tokens.dense,
            color = if (none) colors.error else Color.Unspecified,
        )
        if (none) CamoText("  $noMatches", style = Graft.tokens.dense, color = colors.onSurfaceVariant)
    }
}
