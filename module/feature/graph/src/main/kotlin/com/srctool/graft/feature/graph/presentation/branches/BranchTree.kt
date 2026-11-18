// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.branches

import com.srctool.graft.core.ui.components.SpeedSearchMatcher
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.Ref
import com.srctool.graft.data.git.api.models.RefKind
import com.srctool.graft.data.git.api.models.StashEntry

/** The left panel's G1 sections (Task 14 wireframe). */
internal enum class Section { LOCAL, REMOTE, TAGS, STASHES }

/** One visible row of the left panel, flattened from its sections and folders. [key] is stable across rebuilds. */
internal sealed interface PanelRow {
    val key: String
    val depth: Int

    data class Header(val section: Section, val count: Int, val collapsed: Boolean) : PanelRow {
        override val key: String get() = "section:$section"
        override val depth: Int get() = 0
    }

    /** A `/` folder of branch names, or a remote's node in Remote. */
    data class Folder(
        val section: Section,
        val path: String,
        val name: String,
        override val depth: Int,
        val expanded: Boolean,
        val remote: Boolean = false,
    ) : PanelRow {
        override val key: String get() = "folder:$section:$path"
    }

    data class RefItem(
        val ref: Ref,
        /** What the row shows: the last part of the name in a folder, the whole short name when pinned or flat. */
        val label: String,
        override val depth: Int,
        val hidden: Boolean,
        val solo: Boolean,
        val pinned: Boolean,
        /** The ranges of [label] speed search matched, for highlighting. */
        val matches: List<IntRange>? = null,
    ) : PanelRow {
        override val key: String get() = "ref:${ref.name}"
        val commit: ObjectId get() = ref.commit
    }

    data class StashItem(val entry: StashEntry, val matches: List<IntRange>? = null) : PanelRow {
        override val key: String get() = "stash:${entry.id.hex}"
        override val depth: Int get() = 1
    }

    /** A section with nothing in it, or nothing matching. */
    data class Empty(val section: Section) : PanelRow {
        override val key: String get() = "empty:$section"
        override val depth: Int get() = 1
    }
}

/** What the panel is built from: the refs and stashes, and the saved and session choices. */
internal data class PanelInput(
    val refs: List<Ref>,
    val stashes: List<StashEntry>,
    val hidden: Set<String> = emptySet(),
    val solo: Set<String> = emptySet(),
    val pinned: Set<String> = emptySet(),
    /** Collapsed sections and folders, by row key. Folders start expanded. */
    val collapsed: Set<String> = emptySet(),
    val query: String = "",
)

/**
 * The left panel's rows (Task 14 wireframe): each section's header with its count, then its items. Branches are a tree
 * of `/` folders (remote branches under a node per remote), pinned refs first and flat, then the rest by name; tags and
 * stashes are flat. While speed search has text, only matches and the folders leading to them show, expanded, and a
 * collapsed section still filters.
 */
internal fun panelRows(input: PanelInput): List<PanelRow> {
    val searching = input.query.isNotEmpty()
    val bySection = input.refs.filter { it.symbolicTarget == null }.groupBy { sectionOf(it) }
    val rows = ArrayList<PanelRow>()
    for (section in Section.entries) {
        val header = PanelRow.Header(section, 0, false)
        val items =
            if (section == Section.STASHES) {
                input.stashes.mapNotNull { e ->
                    val m = SpeedSearchMatcher.match(e.message, input.query) ?: return@mapNotNull null
                    PanelRow.StashItem(e, m.takeIf { searching })
                }
            } else {
                branchRows(section, bySection[section].orEmpty(), input)
            }
        val count = if (section == Section.STASHES) input.stashes.size else bySection[section].orEmpty().size
        val collapsed = header.key in input.collapsed && !searching
        rows += PanelRow.Header(section, count, collapsed)
        if (collapsed) continue
        if (items.isEmpty()) rows += PanelRow.Empty(section) else rows += items
    }
    return rows
}

private fun sectionOf(ref: Ref): Section? = when (ref.kind) {
    RefKind.LOCAL_BRANCH -> Section.LOCAL
    RefKind.REMOTE_BRANCH -> Section.REMOTE
    RefKind.TAG -> Section.TAGS
    else -> null
}

private fun branchRows(section: Section, refs: List<Ref>, input: PanelInput): List<PanelRow> {
    val searching = input.query.isNotEmpty()
    fun item(ref: Ref, label: String, depth: Int, matches: List<IntRange>?) = PanelRow.RefItem(
        ref,
        label,
        depth,
        hidden = ref.name in input.hidden,
        solo = ref.name in input.solo,
        pinned = ref.name in input.pinned,
        matches = matches,
    )
    val shown = refs.filter { SpeedSearchMatcher.match(it.shortName, input.query) != null }
    val pinned = shown.filter { it.name in input.pinned }.sortedWith(compareBy({ !it.isHead }, { it.shortName }))
    val rest = shown.filter { it.name !in input.pinned }
    val rows = ArrayList<PanelRow>()
    pinned.forEach {
        rows +=
            item(it, it.shortName, 1, SpeedSearchMatcher.match(it.shortName, input.query).takeIf { searching })
    }
    if (section == Section.TAGS) {
        rest.sortedBy { it.shortName }.forEach {
            rows += item(it, it.shortName, 1, SpeedSearchMatcher.match(it.shortName, input.query).takeIf { searching })
        }
        return rows
    }
    // A tree of `/` parts: remote branches start with their remote's node.
    data class Node(val children: java.util.TreeMap<String, Node> = java.util.TreeMap(), var ref: Ref? = null)
    val root = Node()
    for (ref in rest) {
        var node = root
        for (part in ref.shortName.split('/')) node = node.children.getOrPut(part) { Node() }
        node.ref = ref
    }
    fun walk(node: Node, path: String, depth: Int) {
        // Folders first, then the refs at this level, each by name.
        val (folders, leaves) = node.children.entries.partition { it.value.children.isNotEmpty() }
        for ((name, child) in folders) {
            val childPath = if (path.isEmpty()) name else "$path/$name"
            val folder = PanelRow.Folder(
                section,
                childPath,
                name,
                depth,
                expanded = true,
                remote =
                section == Section.REMOTE && depth == 1,
            )
            val expanded = searching || folder.key !in input.collapsed
            rows += folder.copy(expanded = expanded)
            // A folder that is also a branch name (`feature` and `feature/login`): the branch shows inside it.
            if (expanded) {
                child.ref?.let { rows += item(it, name, depth + 1, null) }
                walk(child, childPath, depth + 1)
            }
        }
        for ((name, child) in leaves) {
            val ref = child.ref ?: continue
            val m = SpeedSearchMatcher.match(ref.shortName, input.query)
            // Matches are on the whole name; shown on the leaf's label only where they fall in it.
            val offset = ref.shortName.length - name.length
            val local = m?.mapNotNull { r ->
                (maxOf(r.first, offset) - offset..r.last - offset).takeIf {
                    r.last >=
                        offset
                }
            }
            rows += item(ref, name, depth, local.takeIf { searching })
        }
    }
    walk(root, "", 1)
    return rows
}

/** The row to move to from [index] for ← (parent) in [rows]: the nearest row above with a smaller depth. */
internal fun parentOf(rows: List<PanelRow>, index: Int): Int? {
    val depth = rows.getOrNull(index)?.depth ?: return null
    return (index - 1 downTo 0).firstOrNull { rows[it].depth < depth }
}

/**
 * The repository's default branch, which "Compare with main" compares against (Task 15): what `origin/HEAD` points to,
 * else a local `main`, else a local `master`. A full ref name, or null when there's none of these.
 */
internal fun defaultBranch(refs: List<Ref>): String? {
    val names = refs.mapTo(HashSet()) { it.name }
    refs.firstOrNull {
        it.name == "refs/remotes/origin/HEAD"
    }?.symbolicTarget?.takeIf { it in names }?.let { return it }
    return listOf("refs/heads/main", "refs/heads/master").firstOrNull { it in names }
}
