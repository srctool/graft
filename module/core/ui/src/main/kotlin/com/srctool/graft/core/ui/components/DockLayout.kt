// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.icon.CamoIcon
import com.srctool.camouflage.core.iconbutton.CamoIconButton
import com.srctool.camouflage.core.menu.CamoMenuAnchor
import com.srctool.camouflage.core.menu.CamoMenuItem
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.surface.CamoSurface
import com.srctool.camouflage.core.text.CamoText
import com.srctool.camouflage.core.theme.Appearance
import com.srctool.camouflage.core.theme.SurfaceLevel
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.core.ui.theme.GraftIcon
import com.srctool.graft.core.ui.theme.graftIcon

/** Where a tool window docks (camouflage#75). */
public enum class DockSide { LEFT, RIGHT, BOTTOM }

/** A tool window: the left panel, the details panel, the Git log. Its [content] is the screen's. */
@Immutable
public class ToolWindow(
    public val id: String,
    public val title: String,
    public val icon: GraftIcon,
    public val defaultSide: DockSide,
    public val content: @Composable () -> Unit,
)

/** What a [DockLayoutState] saves: where each tool window docks, which is open per side, and each dock's size. */
public data class DockLayoutSnapshot(
    val sides: Map<String, DockSide>,
    val open: Map<DockSide, String>,
    val sizes: Map<DockSide, Float>,
)

/**
 * Which tool window is where and which are open, owned by the app so the layout can be saved per repository
 * (UI Design, "Layouts"). One tool window per dock is open at a time; the others wait on the stripe. [toggleSide] is
 * what `Cmd/Alt+1/2/3` call (Keyboard Shortcuts), [hideAll] what focus mode calls.
 */
@Stable
public class DockLayoutState(
    defaultSizes: Map<DockSide, Dp> =
        mapOf(DockSide.LEFT to 260.dp, DockSide.RIGHT to 320.dp, DockSide.BOTTOM to 200.dp),
) {
    private val sides = mutableStateMapOf<String, DockSide>()
    private val opened = mutableStateMapOf<DockSide, String>()
    private val lastOpened = HashMap<DockSide, String>()

    internal val splits: Map<DockSide, SplitPaneState> =
        DockSide.entries.associateWith { SplitPaneState(defaultSizes.getValue(it), minSize = 120.dp) }

    /** Where [id] docks; its window's default side until it's moved. */
    public fun side(id: String, default: DockSide): DockSide = sides[id] ?: default

    public fun open(side: DockSide): String? = opened[side]

    public fun isOpen(id: String): Boolean = id in opened.values

    /** Opens [id] in its dock, closing whatever was open there, or closes it if it's open. */
    public fun toggle(window: ToolWindow) {
        val side = side(window.id, window.defaultSide)
        if (opened[side] == window.id) close(side) else show(window.id, side)
    }

    public fun show(id: String, side: DockSide) {
        opened[side] = id
        lastOpened[side] = id
    }

    public fun close(side: DockSide) {
        opened.remove(side)
    }

    /** Opens [side]'s dock with what it showed last (or [fallback]), or closes it if it's open. */
    public fun toggleSide(side: DockSide, fallback: String?) {
        if (opened[side] != null) {
            close(side)
            return
        }
        (lastOpened[side] ?: fallback)?.let { show(it, side) }
    }

    /** Opens [id] in [side], or closes that dock when [id] is what's open there. */
    public fun toggleSideOf(side: DockSide, id: String) {
        if (opened[side] == id) close(side) else show(id, side)
    }

    /** Moves [window] to [to], open there if it was open before. */
    public fun move(window: ToolWindow, to: DockSide) {
        val from = side(window.id, window.defaultSide)
        if (from == to) return
        val wasOpen = opened[from] == window.id
        if (wasOpen) close(from)
        sides[window.id] = to
        if (wasOpen) show(window.id, to)
    }

    /** Every dock closed; the center alone remains. */
    public fun hideAll() {
        opened.clear()
    }

    private var beforeFocus: DockLayoutSnapshot? by mutableStateOf(null)

    /** Focus mode is on: [toggleFocusMode] hid every dock and will bring them back. */
    public val focusMode: Boolean get() = beforeFocus != null

    /** Focus mode (`Mod+Shift+F12`): hides every dock, then the next call restores the layout it hid. */
    public fun toggleFocusMode() {
        val before = beforeFocus
        if (before == null) {
            beforeFocus = current()
            hideAll()
        } else {
            beforeFocus = null
            restore(before)
        }
    }

    /** What to save: while focus mode hides the docks, the layout it will bring back, so a restart never starts hidden. */
    public fun snapshot(): DockLayoutSnapshot = beforeFocus ?: current()

    private fun current() = DockLayoutSnapshot(sides.toMap(), opened.toMap(), splits.mapValues { it.value.size.value })

    public fun restore(snapshot: DockLayoutSnapshot) {
        beforeFocus = null
        sides.clear()
        sides.putAll(snapshot.sides)
        opened.clear()
        opened.putAll(snapshot.open)
        lastOpened.putAll(snapshot.open)
        snapshot.sizes.forEach { (side, size) -> splits[side]?.resize(size.dp) }
    }
}

/**
 * IDE-style docks around [center] (camouflage#75, UI Design "Main window"): left, right and bottom docks holding
 * [toolWindows], each opened from its icon on the edge stripes (left and bottom windows on the left stripe, right
 * windows on the right one) and resized through a [SplitPane]. A tool window's header can hide it or move it to
 * another dock; Esc in a tool window returns focus to the center.
 */
@Composable
public fun DockLayout(
    state: DockLayoutState,
    toolWindows: List<ToolWindow>,
    modifier: Modifier = Modifier,
    center: @Composable () -> Unit,
) {
    val centerFocus = remember { FocusRequester() }
    val bySide = toolWindows.groupBy { state.side(it.id, it.defaultSide) }
    fun openWindow(side: DockSide): ToolWindow? =
        state.open(side)?.let { id -> toolWindows.firstOrNull { it.id == id } }

    Row(modifier.fillMaxSize()) {
        Stripe(
            top = bySide[DockSide.LEFT].orEmpty(),
            bottom = bySide[DockSide.BOTTOM].orEmpty(),
            state = state,
        )
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val centerAndBottom: @Composable () -> Unit = {
                val bottom = openWindow(DockSide.BOTTOM)
                val main: @Composable () -> Unit = {
                    // The center sits on the window's surface, a layer above the docks' panels.
                    Box(Modifier.fillMaxSize().background(Camouflage.colors.surface).focusRequester(centerFocus)) {
                        center()
                    }
                }
                if (bottom == null) {
                    main()
                } else {
                    SplitPane(
                        state.splits.getValue(DockSide.BOTTOM),
                        orientation = Orientation.Vertical,
                        sizedPane = SizedPane.SECOND,
                        first = main,
                        second = { ToolWindowFrame(bottom, state, centerFocus) },
                    )
                }
            }
            val withRight: @Composable () -> Unit = {
                val right = openWindow(DockSide.RIGHT)
                if (right == null) {
                    centerAndBottom()
                } else {
                    SplitPane(
                        state.splits.getValue(DockSide.RIGHT),
                        sizedPane = SizedPane.SECOND,
                        first = centerAndBottom,
                        second = { ToolWindowFrame(right, state, centerFocus) },
                    )
                }
            }
            val left = openWindow(DockSide.LEFT)
            if (left == null) {
                withRight()
            } else {
                SplitPane(
                    state.splits.getValue(DockSide.LEFT),
                    first = { ToolWindowFrame(left, state, centerFocus) },
                    second = withRight,
                )
            }
        }
        Stripe(top = bySide[DockSide.RIGHT].orEmpty(), bottom = emptyList(), state = state)
    }
}

@Composable
private fun Stripe(top: List<ToolWindow>, bottom: List<ToolWindow>, state: DockLayoutState) {
    val density = Graft.tokens.density
    val width = density.control + 8.dp
    Column(
        Modifier.width(
            width,
        ).fillMaxHeight().background(Camouflage.colors.surfaceContainerLowest).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        top.forEach { StripeButton(it, state) }
        Spacer(Modifier.weight(1f))
        bottom.forEach { StripeButton(it, state) }
    }
}

@Composable
private fun StripeButton(window: ToolWindow, state: DockLayoutState) {
    val size = Graft.tokens.density.control + 4.dp
    CamoIconButton(
        icon = {
            CamoIcon(
                graftIcon(window.icon),
                contentDescription = null,
                modifier = Modifier.size(Graft.tokens.density.icon),
            )
        },
        contentDescription = window.title,
        onClick = { state.toggle(window) },
        modifier = Modifier.size(size),
        appearance = Appearance.Ghost,
        checked = state.isOpen(window.id),
        tooltip = window.title,
    )
}

@Composable
private fun ToolWindowFrame(window: ToolWindow, state: DockLayoutState, centerFocus: FocusRequester) {
    val density = Graft.tokens.density
    val side = state.side(window.id, window.defaultSide)
    val moveLabel = graftText("ui.toolWindow.moveTo", "Move {title}", "title" to window.title)
    val hideLabel = graftText("ui.toolWindow.hide", "Hide {title}", "title" to window.title)
    val sideNames =
        mapOf(
            DockSide.LEFT to graftText("ui.toolWindow.left", "Left"),
            DockSide.RIGHT to graftText("ui.toolWindow.right", "Right"),
            DockSide.BOTTOM to graftText("ui.toolWindow.bottom", "Bottom"),
        )
    CamoSurface(
        modifier =
        Modifier.fillMaxSize().onKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                centerFocus.requestFocus()
                true
            } else {
                false
            }
        },
        level = SurfaceLevel.Lowest,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().height(density.control + 4.dp).padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CamoText(
                    window.title,
                    modifier = Modifier.weight(1f).semantics {
                        heading()
                    },
                    style = Graft.tokens.denseStrong,
                )
                val moves =
                    DockSide.entries.filter { it != side }.map { to ->
                        CamoMenuItem(sideNames.getValue(to), onClick = { state.move(window, to) })
                    }
                CamoMenuAnchor(items = moves) { open ->
                    HeaderButton(GraftIcon.OVERFLOW_MENU, moveLabel, open)
                }
                HeaderButton(GraftIcon.SUBTRACT, hideLabel) { state.close(side) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { window.content() }
        }
    }
}

@Composable
private fun HeaderButton(icon: GraftIcon, label: String, onClick: () -> Unit) {
    val size = Graft.tokens.density.control
    CamoIconButton(
        icon = {
            CamoIcon(graftIcon(icon), contentDescription = null, modifier = Modifier.size(Graft.tokens.density.icon))
        },
        contentDescription = label,
        onClick = onClick,
        modifier = Modifier.size(size),
        appearance = Appearance.Ghost,
        tooltip = label,
    )
}
