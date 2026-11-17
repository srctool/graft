// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.branches

import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.Ref
import com.srctool.graft.data.git.api.models.StashEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class BranchTreeTest {
    private val id = ObjectId("1".repeat(40))
    private val refs =
        listOf(
            Ref("refs/heads/main", id, isHead = true),
            Ref("refs/heads/feature/login", id),
            Ref("refs/heads/feature/logout", id),
            Ref("refs/heads/old-work", id),
            Ref("refs/remotes/origin/main", id),
            Ref("refs/remotes/origin/HEAD", id, symbolicTarget = "refs/remotes/origin/main"),
            Ref("refs/remotes/origin/release/1.4", id),
            Ref("refs/tags/v1.0", id),
            Ref("refs/stash", id),
        )
    private val stash = StashEntry(0, ObjectId("2".repeat(40)), id, "main", "WIP on main: fix", 0)

    private fun flags(row: PanelRow.RefItem) = listOfNotNull(
        "pinned".takeIf { row.pinned },
        "hidden".takeIf { row.hidden },
        "solo".takeIf { row.solo },
    ).joinToString("") { " [$it]" }

    private fun List<PanelRow>.shape(): List<String> = map { row ->
        "  ".repeat(row.depth) +
            when (row) {
                is PanelRow.Header -> "${row.section} ${row.count}" + if (row.collapsed) " (collapsed)" else ""
                is PanelRow.Folder -> row.name + "/" + if (row.expanded) "" else " (collapsed)"
                is PanelRow.RefItem -> row.label + flags(row)
                is PanelRow.StashItem -> "stash ${row.entry.index}"
                is PanelRow.Empty -> "(empty)"
            }
    }

    @Test
    fun `sections hold folders, pinned refs first, and counts`() {
        val rows =
            panelRows(
                PanelInput(
                    refs,
                    listOf(stash),
                    hidden = setOf("refs/heads/old-work"),
                    solo = setOf("refs/heads/feature/login"),
                    pinned = setOf("refs/heads/main"),
                ),
            )
        assertEquals(
            listOf(
                "LOCAL 4",
                "  main [pinned]",
                "  feature/",
                "    login [solo]",
                "    logout",
                "  old-work [hidden]",
                "REMOTE 2",
                "  origin/",
                "    release/",
                "      1.4",
                "    main",
                "TAGS 1",
                "  v1.0",
                "STASHES 1",
                "  stash 0",
            ),
            rows.shape(),
        )
    }

    @Test
    fun `collapsing hides a section's or a folder's rows, and an empty section says so`() {
        val rows =
            panelRows(
                PanelInput(
                    refs.filterNot { it.name.startsWith("refs/tags/") },
                    emptyList(),
                    collapsed = setOf("section:REMOTE", "folder:LOCAL:feature"),
                ),
            )
        assertEquals(
            listOf(
                "LOCAL 4",
                "  feature/ (collapsed)",
                "  main",
                "  old-work",
                "REMOTE 2 (collapsed)",
                "TAGS 0",
                "  (empty)",
                "STASHES 0",
                "  (empty)",
            ),
            rows.shape(),
        )
    }

    @Test
    fun `speed search keeps matches and the folders to them, collapsed or not`() {
        val rows =
            panelRows(
                PanelInput(
                    refs,
                    listOf(stash),
                    collapsed = setOf("section:REMOTE", "folder:LOCAL:feature"),
                    query = "logi",
                ),
            )
        assertEquals(
            listOf(
                "LOCAL 4",
                "  feature/",
                "    login",
                "REMOTE 2",
                "  (empty)",
                "TAGS 1",
                "  (empty)",
                "STASHES 1",
                "  (empty)",
            ),
            rows.shape(),
        )
        val login = rows.filterIsInstance<PanelRow.RefItem>().single()
        assertEquals(listOf(0..3), login.matches, "the match lands on the leaf's own label")
    }

    @Test
    fun `left goes to the parent row`() {
        val rows = panelRows(PanelInput(refs, emptyList()))
        val login = rows.indexOfFirst { it.key == "ref:refs/heads/feature/login" }
        assertEquals("folder:LOCAL:feature", rows[parentOf(rows, login)!!].key)
        assertEquals("section:LOCAL", rows[parentOf(rows, login - 1)!!].key)
        assertEquals(null, parentOf(rows, 0))
    }
}
