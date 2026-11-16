// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.git

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.srctool.camouflage.core.icon.CamoIcon
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.camouflage.core.text.CamoText
import com.srctool.graft.core.ui.l10n.graftText
import com.srctool.graft.core.ui.theme.Graft
import com.srctool.graft.core.ui.theme.GraftIcon
import com.srctool.graft.core.ui.theme.graftIcon

/** What a ref label names (Commit Graph, "What appears on the graph"). */
public enum class RefLabelKind { LOCAL, REMOTE, TAG }

/**
 * A ref label on the graph: a local branch (filled), a remote branch (outlined, with the remote icon) or a tag. A local
 * branch at the same commit as its upstream carries it in [mergedRemote] and reads `main ⇄ origin/main`.
 */
@Immutable
public data class RefLabel(
    val name: String,
    val kind: RefLabelKind,
    /** The checked-out branch: bold, with the HEAD marker. */
    val head: Boolean = false,
    val mergedRemote: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
)

/**
 * A ref label as a Carbon tag (UI Design, "Graph style"): a pill, the one rounded element, tinted with the branch's
 * lane [color]. Its icon and outline tell local, remote and tag apart without the color, and screen readers hear the
 * whole label ("Branch main, checked out, also origin/main, 2 ahead, 3 behind").
 */
@Composable
public fun RefChip(ref: RefLabel, color: Color, modifier: Modifier = Modifier) {
    val colors = Camouflage.colors
    val tint = color.copy(alpha = 0.22f).compositeOver(colors.surface)
    val shape = RoundedCornerShape(percent = 50)
    val icon =
        when (ref.kind) {
            RefLabelKind.LOCAL -> GraftIcon.BRANCH
            RefLabelKind.REMOTE -> GraftIcon.CLOUD
            RefLabelKind.TAG -> GraftIcon.TAG
        }
    val label = refLabelText(ref)
    val description = refDescription(ref)
    val style = Graft.tokens.dense.copy(fontSize = 12.sp, lineHeight = 16.sp)
    val chip =
        when (ref.kind) {
            RefLabelKind.REMOTE -> Modifier.border(1.dp, color, shape)
            else -> Modifier.background(tint, shape)
        }
    Row(
        modifier
            .height(18.dp)
            .then(chip)
            .padding(horizontal = 6.dp)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ref.head) {
            CamoIcon(
                graftIcon(GraftIcon.CHECKMARK),
                contentDescription = null,
                modifier = Modifier.size(12.dp).padding(end = 2.dp),
                tint = colors.onSurface,
            )
        }
        CamoIcon(graftIcon(icon), contentDescription = null, modifier = Modifier.size(12.dp), tint = colors.onSurface)
        CamoText(
            label,
            modifier = Modifier.padding(start = 4.dp),
            style = if (ref.head) style.copy(fontWeight = FontWeight.SemiBold) else style,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** What a chip shows: the name, the merged remote, then `↑2 ↓3`. */
public fun refLabelText(ref: RefLabel): String = buildString {
    append(ref.name)
    ref.mergedRemote?.let { append(" ⇄ ").append(it) }
    val counts = aheadBehind(ref.ahead, ref.behind)
    if (counts.isNotEmpty()) append("  ").append(counts)
}

/** `↑2 ↓3`, either half left out when it's zero. */
public fun aheadBehind(ahead: Int, behind: Int): String = listOfNotNull(
    "↑$ahead".takeIf { ahead > 0 },
    "↓$behind".takeIf { behind > 0 },
).joinToString(" ")

@Composable
private fun refDescription(ref: RefLabel): String {
    val kind =
        when (ref.kind) {
            RefLabelKind.LOCAL -> graftText("ui.ref.branch", "Branch {name}", "name" to ref.name)
            RefLabelKind.REMOTE -> graftText("ui.ref.remote", "Remote branch {name}", "name" to ref.name)
            RefLabelKind.TAG -> graftText("ui.ref.tag", "Tag {name}", "name" to ref.name)
        }
    val parts = mutableListOf(kind)
    if (ref.head) parts += graftText("ui.ref.head", "checked out")
    ref.mergedRemote?.let { parts += graftText("ui.ref.alsoRemote", "also {name}", "name" to it) }
    if (ref.ahead > 0) parts += graftText("ui.ref.ahead", "{count} ahead", "count" to ref.ahead)
    if (ref.behind > 0) parts += graftText("ui.ref.behind", "{count} behind", "count" to ref.behind)
    return parts.joinToString(", ")
}

/** A check's or a pipeline's state (Provider Model's neutral `Check`), for [StatusDot]. */
public enum class CheckState { SUCCESS, FAILURE, PENDING, NEUTRAL }

/**
 * A small status mark on branch labels and commits (CI and Actions). Each state has its own shape as well as its
 * color: a filled dot (success), a cross (failure), a ring (pending), a dash (neutral or skipped). [label] is what
 * screen readers hear, for example "Checks passed".
 */
@Composable
public fun StatusDot(state: CheckState, label: String, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    val colors = Camouflage.colors
    val color =
        when (state) {
            CheckState.SUCCESS -> colors.success
            CheckState.FAILURE -> colors.error
            CheckState.PENDING -> colors.warning
            CheckState.NEUTRAL -> colors.onSurfaceVariant
        }
    Canvas(modifier.size(size).semantics { contentDescription = label }) {
        val r = this.size.minDimension / 2
        val c = center
        val line = 2.dp.toPx()
        when (state) {
            CheckState.SUCCESS -> drawCircle(color, r, c)

            CheckState.FAILURE -> {
                val d = r * 0.75f
                drawLine(color, Offset(c.x - d, c.y - d), Offset(c.x + d, c.y + d), line, StrokeCap.Round)
                drawLine(color, Offset(c.x - d, c.y + d), Offset(c.x + d, c.y - d), line, StrokeCap.Round)
            }

            CheckState.PENDING -> drawCircle(color, r - line / 2, c, style = Stroke(line))

            CheckState.NEUTRAL -> drawLine(
                color,
                Offset(c.x - r * 0.75f, c.y),
                Offset(c.x + r * 0.75f, c.y),
                line,
                StrokeCap.Round,
            )
        }
    }
}

/**
 * Up to two letters for [name]: the first letters of its first and last words ("Dwiki Riyadi" → "DR", "ann" → "A"),
 * whole code points so any script works; `[bot]` and email domains are left out. Empty when there's no letter.
 */
public fun initials(name: String): String {
    val words =
        name.substringBefore('@').replace("[bot]", "")
            .split(Regex("[\\s._-]+"))
            .filter { w -> w.codePoints().anyMatch(Character::isLetter) }
    fun first(word: String): String {
        val cp = word.codePoints().filter(Character::isLetter).findFirst().asInt
        return String(Character.toChars(Character.toUpperCase(cp)))
    }
    return when (words.size) {
        0 -> ""
        1 -> first(words[0])
        else -> first(words.first()) + first(words.last())
    }
}

/**
 * An author's initials in a circle, until avatars arrive with G6 (they need the network). The circle's tint comes from
 * [key] (the author's email), so an author keeps one color everywhere; a bot shows the bot icon instead. Decorative:
 * the row it's in names the author.
 */
@Composable
public fun AuthorInitials(
    name: String,
    key: String,
    modifier: Modifier = Modifier,
    bot: Boolean = false,
    size: Dp = 18.dp,
) {
    val colors = Camouflage.colors
    val lane = Graft.tokens.lanes[key.lowercase().hashCode()]
    Box(
        modifier
            .size(size)
            .background(lane.copy(alpha = 0.28f).compositeOver(colors.surface), CircleShape)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        if (bot) {
            CamoIcon(
                graftIcon(GraftIcon.BOT),
                contentDescription = null,
                modifier = Modifier.size(size * 0.65f),
                tint = colors.onSurface,
            )
        } else {
            CamoText(
                initials(name),
                style = Graft.tokens.denseStrong.copy(
                    fontSize = (size.value * 0.45f).sp,
                    lineHeight = (
                        size.value *
                            0.5f
                        ).sp,
                ),
                color = colors.onSurface,
                maxLines = 1,
            )
        }
    }
}
