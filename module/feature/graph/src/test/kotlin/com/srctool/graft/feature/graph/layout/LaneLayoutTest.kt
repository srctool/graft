// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.layout

import com.srctool.graft.data.git.api.models.CommitRow
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.feature.graph.layout.GraphEdge.Kind
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lane layout on the Testing Strategy's cases. Each case is checked against the layout invariants and against a
 * golden file under `fixtures/layout/` (re-record with `-PrecordFixtures`, then review the diff).
 */
class LaneLayoutTest {
    @Test
    fun `linear history stays in one lane and one color`() {
        val rows = layout(history("C: B", "B: A", "A:"))
        assertTrue(rows.all { it.lane == 0 && it.color == rows[0].color && it.width == 1 })
        golden("linear", rows)
    }

    @Test
    fun `branch and merge`() {
        val rows = layout(history("M: C F", "F: B", "C: B", "B: A", "A:"))
        assertEquals(listOf(0, 1, 0, 0, 0), rows.map { it.lane })
        // The merged branch has its own color; the first-parent line keeps main's.
        assertEquals(rows[0].color, rows.single { it.commit.subject == "C" }.color)
        assertTrue(rows.single { it.commit.subject == "F" }.color != rows[0].color)
        golden("branch-and-merge", rows)
    }

    @Test
    fun `criss-cross merge`() {
        val rows =
            layout(history("X1: A2 B2", "Y1: B2 A2", "A2: A1 B1", "B2: B1 A1", "A1: R", "B1: R", "R:"))
        golden("criss-cross", rows)
    }

    @Test
    fun `octopus merge fans in one lane per parent`() {
        val rows = layout(history("O: M A B C", "A: M", "B: M", "C: M", "M:"))
        assertEquals(4, rows[0].edges().count { it.kind == Kind.BOTTOM })
        assertEquals(listOf(0, 1, 2, 3), rows[0].edges().filter { it.kind == Kind.BOTTOM }.map { it.to })
        assertEquals(4, rows.last().edges().count { it.kind == Kind.TOP })
        golden("octopus", rows)
    }

    @Test
    fun `several roots each start their own column`() {
        val rows = layout(history("M: A D", "Z: Y", "A:", "Y:", "D:"))
        val roots = rows.filter { it.commit.isRoot }
        assertEquals(3, roots.size)
        assertTrue(roots.all { row -> row.edges().none { it.kind == Kind.BOTTOM } })
        golden("several-roots", rows)
    }

    @Test
    fun `a long-running branch keeps its lane and color across merges`() {
        val rows = layout(history("M3: M2 D3", "D3: D2 M1", "M2: M1", "D2: D1", "M1: M0", "D1: M0", "M0:"))
        val develop = rows.filter { it.commit.subject.startsWith("D") }
        assertEquals(1, develop.map { it.lane }.distinct().size)
        assertEquals(1, develop.map { it.color }.distinct().size)
        golden("long-running-branch", rows)
    }

    @Test
    fun `a new branch tip reuses a freed lane`() {
        // B's line ends at A and A is a root, so both lanes are free again; the unrelated tip Z takes lane 0.
        val rows = layout(history("T: A B", "B: A", "A:", "Z: Y", "Y:"))
        assertEquals(0, rows.single { it.commit.subject == "Z" }.lane)
        assertTrue(rows.all { it.width <= 2 })
        golden("lane-reuse", rows)
    }

    @Test
    fun `the topology of data-git's graph fixture repository`() {
        // GraphRepo in :module:data:git's tests: two roots, a merge, a merged second root, an octopus, an empty
        // commit and the crafted Latin-1 commit, in the order `git log --topo-order` printed them.
        val rows =
            layout(
                history(
                    "crafted: empty", "empty: octopus", "octopus: merge-docs work-a work-b work-c",
                    "work-c: merge-docs", "work-b: merge-docs", "work-a: merge-docs",
                    "merge-docs: merge-feature docs-root", "docs-root:", "merge-feature: main-2 feature-work",
                    "feature-work: init", "main-2: init", "init:",
                ),
            )
        golden("graph-repo", rows)
    }

    @Test
    fun `laidOut lays out a stream, starting fresh on each collection`() = runBlocking {
        val commits = history("M: C F", "F: B", "C: B", "B: A", "A:").asFlow().laidOut()
        val first = commits.toList()
        val second = commits.toList()
        assertEquals(render(layout(history("M: C F", "F: B", "C: B", "B: A", "A:"))), render(first))
        assertEquals(render(first), render(second))
    }

    @Test
    fun `the synthetic benchmark history keeps the invariants`() {
        layout(LaneLayoutBenchmarkTest.synthetic(20_000))
    }

    companion object {
        /**
         * Commits in topological order, one per entry: `"name: parent1 parent2"`. Hashes are derived from the order
         * names first appear, and each subject is the name.
         */
        fun history(vararg entries: String): List<CommitRow> {
            val parsed = entries.map { e -> e.substringBefore(':').trim() to e.substringAfter(':').trim() }
            val ids = parsed.withIndex().associate { (i, p) -> p.first to ObjectId("%040x".format(i + 1L)) }
            return parsed.map { (name, parents) ->
                CommitRow(
                    id = ids.getValue(name),
                    parents = parents.split(' ').filter { it.isNotEmpty() }.map { ids.getValue(it) },
                    authorIndex = 0,
                    authorTime = 0,
                    committerIndex = 0,
                    committerTime = 0,
                    subject = name,
                )
            }
        }

        /** Lays out [commits] and checks every row against the layout's invariants. */
        internal fun layout(commits: List<CommitRow>): List<GraphRow> {
            val lanes = LaneLayout()
            val rows = commits.map(lanes::next)
            var above = emptyMap<Int, Int>() // lane -> color at the bottom of the previous row
            rows.forEach { row ->
                val edges = row.edges()
                val name = row.commit.subject
                val top = edges.filter { it.kind != Kind.BOTTOM }.associate { it.from to it.color }
                val bottom = edges.filter { it.kind != Kind.TOP }.associate { it.to to it.color }
                assertEquals(above, top, "lanes entering $name are the lanes leaving the row above")
                assertTrue(
                    edges.filter {
                        it.kind == Kind.TOP
                    }.all { it.to == row.lane },
                    "$name: TOP ends at the node",
                )
                assertTrue(
                    edges.filter { it.kind == Kind.BOTTOM }.all { it.from == row.lane },
                    "$name: BOTTOM starts at the node",
                )
                assertTrue(edges.filter { it.kind == Kind.THROUGH }.all { it.from == it.to && it.from != row.lane })
                assertTrue(edges.all { it.from < row.width && it.to < row.width }, "$name fits its width")
                assertEquals(
                    row.commit.parents.size,
                    edges.count {
                        it.kind == Kind.BOTTOM
                    },
                    "$name: one line per parent",
                )
                if (row.commit.parents.isNotEmpty()) {
                    val firstParent = edges.first { it.kind == Kind.BOTTOM }
                    assertEquals(row.lane, firstParent.to, "$name: the first parent keeps the lane")
                    assertEquals(row.color, firstParent.color, "$name: the first parent keeps the color")
                }
                above = bottom
            }
            assertEquals(emptyMap(), above, "every line ends at a commit")
            return rows
        }

        /**
         * One line per row: the lanes (`*` the node, `|` a line in the lane), the subject, then the edges. Colors are
         * the layout's numbers.
         */
        internal fun render(rows: List<GraphRow>): String = buildString {
            val laneWidth = rows.maxOfOrNull { it.width } ?: 0
            rows.forEach { row ->
                val lanes = CharArray(laneWidth * 2) { ' ' }
                row.edges().forEach { e ->
                    if (e.kind == Kind.THROUGH) lanes[e.from * 2] = '|'
                    if (e.kind == Kind.TOP && e.from != row.lane) lanes[e.from * 2] = '/'
                    if (e.kind == Kind.BOTTOM && e.to != row.lane) lanes[e.to * 2] = '\\'
                }
                lanes[row.lane * 2] = '*'
                append(String(lanes)).append(' ')
                append(row.commit.subject.ifEmpty { "(empty)" }.padEnd(16))
                append(" lane ").append(row.lane).append(" c").append(row.color)
                append(" | ").append(row.edges().joinToString(", "))
                append('\n')
            }
        }

        private fun golden(case: String, rows: List<GraphRow>) = Golden.check("layout", case, render(rows))
    }
}
