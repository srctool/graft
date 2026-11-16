// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.graph

import com.srctool.graft.core.ui.git.RefLabel
import com.srctool.graft.core.ui.git.RefLabelKind
import com.srctool.graft.data.git.api.models.Ref
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GraphLogicTest {
    @Test
    fun `refs become labels with HEAD first, an upstream at the same commit merged, symbolic refs left out`() {
        val tip = Sample.id(5)
        val labels =
            refLabels(
                listOf(
                    Ref("refs/heads/zeta", tip),
                    Ref(
                        "refs/heads/main",
                        tip,
                        isHead = true,
                        upstream = "refs/remotes/origin/main",
                        ahead = 2,
                        behind = 3,
                    ),
                    Ref("refs/remotes/origin/main", tip),
                    Ref("refs/remotes/origin/HEAD", tip, symbolicTarget = "refs/remotes/origin/main"),
                    Ref("refs/remotes/upstream/main", tip),
                    Ref("refs/tags/v1.0", Sample.id(9), peeled = tip),
                    Ref("refs/stash", tip),
                ),
            ).getValue(tip)
        assertEquals(
            listOf(
                RefLabel("main", RefLabelKind.LOCAL, head = true, mergedRemote = "origin/main", ahead = 2, behind = 3),
                RefLabel("zeta", RefLabelKind.LOCAL),
                RefLabel("upstream/main", RefLabelKind.REMOTE),
                RefLabel("v1.0", RefLabelKind.TAG),
            ),
            labels,
        )
    }

    @Test
    fun `an upstream at another commit keeps its own chip`() {
        val labels =
            refLabels(
                listOf(
                    Ref("refs/heads/main", Sample.id(5), upstream = "refs/remotes/origin/main"),
                    Ref("refs/remotes/origin/main", Sample.id(4)),
                ),
            )
        assertNull(labels.getValue(Sample.id(5)).single().mergedRemote)
        assertEquals(RefLabelKind.REMOTE, labels.getValue(Sample.id(4)).single().kind)
    }

    @Test
    fun `moves stop at the graph's edges and cycle a merge's other parents`() {
        // An octopus at the top: parents in rows 1, 2 and 3.
        val rows =
            listOf(
                com.srctool.graft.data.git.api.models.CommitRow(
                    Sample.id(9),
                    listOf(Sample.id(3), Sample.id(4), Sample.id(5)),
                    0,
                    0,
                    0,
                    0,
                    "Octopus",
                ),
                com.srctool.graft.data.git.api.models.CommitRow(Sample.id(3), emptyList(), 0, 0, 0, 0, "a"),
                com.srctool.graft.data.git.api.models.CommitRow(Sample.id(4), emptyList(), 0, 0, 0, 0, "b"),
                com.srctool.graft.data.git.api.models.CommitRow(Sample.id(5), emptyList(), 0, 0, 0, 0, "c"),
            )
        val moves = GraphMoves(rows.size) { rows[it] }
        assertEquals(1, moves.firstParent(0))
        assertNull(moves.firstParent(1), "a root has no parent")
        assertEquals(listOf(2, 3, 2), (0..2).map { moves.otherParent(0, it) })
        assertNull(moves.otherParent(1, 0))
        assertEquals(0, moves.firstChild(3))
        assertNull(moves.firstChild(0))
        assertEquals(3, moves.nextRef(0, +1) { it == 3 })
        assertNull(moves.nextRef(0, -1) { true })
        assertEquals(2, moves.rowOf(Sample.id(4)))
    }

    @Test
    fun `ages are relative under a week, then a date`() {
        val now = 1_800_000_000L
        assertEquals(Age.JustNow, age(now - 30, now))
        assertEquals(Age.Minutes(5), age(now - 300, now))
        assertEquals(Age.Hours(2), age(now - 7_200, now))
        assertEquals(Age.Days(6), age(now - 6 * 86_400, now))
        assertEquals(Age.Date("Jan 8, 2027"), age(now - 7 * 86_400, now, ZoneOffset.UTC, Locale.US))
    }
}
