// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.source.local

import com.srctool.graft.core.git.GitCommand
import com.srctool.graft.core.git.GitParseException
import com.srctool.graft.data.git.api.models.ChangeEntry
import com.srctool.graft.data.git.api.models.ChangeStatus
import com.srctool.graft.data.git.api.models.ConflictKind
import com.srctool.graft.data.git.api.models.FileModes
import com.srctool.graft.data.git.api.models.SubmoduleChange
import com.srctool.graft.data.git.api.models.WorkingTree

/**
 * `status --porcelain=v2 -z --untracked-files=all`: every changed path, untracked files one by one. Ignored paths
 * only when asked for (`--ignored=traditional`, which with every untracked file listed shows an ignored folder by the
 * files in it).
 */
internal class WorkingTreeCommand(includeIgnored: Boolean) : GitCommand<WorkingTree>() {
    override val function = "STAT"
    override val args =
        listOf("status", "--porcelain=v2", "-z", "--untracked-files=all") +
            if (includeIgnored) listOf("--ignored=traditional") else emptyList()

    override fun parse(stdout: ByteArray): WorkingTree = WorkingTreeParser.parse(stdout)
}

/** Reads `status --porcelain=v2 -z`: NUL-separated records, paths raw (no quoting), a rename's origin its own record. */
internal object WorkingTreeParser {
    private const val STATUS_LETTERS = ".MTADRC"

    fun parse(stdout: ByteArray): WorkingTree {
        val entries = ArrayList<ChangeEntry>()
        var originalFor = -1
        var from = 0
        while (from < stdout.size) {
            var to = from
            while (to < stdout.size && stdout[to] != 0.toByte()) to++
            if (to > from) {
                val text = GitText.decode(stdout, from, to - from)
                if (originalFor >= 0) {
                    entries[originalFor] = entries[originalFor].copy(originalPath = text)
                    originalFor = -1
                } else if (!text.startsWith("# ")) {
                    val entry = entry(text)
                    entries += entry
                    if (text.startsWith("2 ")) originalFor = entries.lastIndex
                }
            }
            from = to + 1
        }
        if (originalFor >= 0) throw GitParseException("a rename without its original path")
        return WorkingTree(entries)
    }

    private fun entry(text: String): ChangeEntry = when (text.firstOrNull()) {
        '1' -> tracked(fields(text, 9), rename = false)
        '2' -> tracked(fields(text, 10), rename = true)
        'u' -> unmerged(fields(text, 11))
        '?' -> ChangeEntry(path = pathAfterMarker(text), untracked = true)
        '!' -> ChangeEntry(path = pathAfterMarker(text), ignored = true)
        else -> throw GitParseException("status record \"${text.take(20)}\"")
    }

    private fun pathAfterMarker(text: String): String =
        text.substring(2.coerceAtMost(text.length)).ifEmpty { throw GitParseException("status entry without a path") }

    private fun fields(text: String, count: Int): List<String> {
        val parts = text.split(' ', limit = count)
        if (parts.size != count) throw GitParseException("status record has ${parts.size} fields, expected $count")
        return parts
    }

    /** `1 XY sub mH mI mW hH hI path` or, for a rename or copy, `2 XY sub mH mI mW hH hI Xscore path`. */
    private fun tracked(parts: List<String>, rename: Boolean): ChangeEntry {
        val xy = parts[1]
        return ChangeEntry(
            path = parts.last().ifEmpty { throw GitParseException("status entry without a path") },
            index = side(xy, 0),
            worktree = side(xy, 1),
            submodule = submodule(parts[2]),
            modes = FileModes(head = parts[3], index = parts[4], worktree = parts[5]),
        ).also {
            if (rename && it.index != ChangeStatus.RENAMED && it.index != ChangeStatus.COPIED &&
                it.worktree != ChangeStatus.RENAMED && it.worktree != ChangeStatus.COPIED
            ) {
                throw GitParseException("a rename record whose status is $xy")
            }
        }
    }

    /** `u XY sub m1 m2 m3 mW h1 h2 h3 path`. */
    private fun unmerged(parts: List<String>): ChangeEntry {
        val kind = ConflictKind.of(parts[1]) ?: throw GitParseException("unmerged status \"${parts[1]}\"")
        return ChangeEntry(
            path = parts.last().ifEmpty { throw GitParseException("status entry without a path") },
            conflict = kind,
            submodule = submodule(parts[2]),
        )
    }

    private fun side(xy: String, at: Int): ChangeStatus? {
        if (xy.length != 2 || xy.any { it !in STATUS_LETTERS }) throw GitParseException("status \"$xy\"")
        return when (xy[at]) {
            '.' -> null
            'M' -> ChangeStatus.MODIFIED
            'T' -> ChangeStatus.TYPE_CHANGED
            'A' -> ChangeStatus.ADDED
            'D' -> ChangeStatus.DELETED
            'R' -> ChangeStatus.RENAMED
            else -> ChangeStatus.COPIED
        }
    }

    /** `N...` for a file, `S` plus `C` (commit changed), `M` (tracked changes) and `U` (untracked) or `.` for a submodule. */
    private fun submodule(field: String): SubmoduleChange? {
        if (field.length != 4 || field[0] !in "NS") throw GitParseException("submodule field \"$field\"")
        if (field[0] == 'N') return null
        return SubmoduleChange(
            commitChanged = field[1] == 'C',
            trackedModified = field[2] == 'M',
            untracked =
            field[3] == 'U',
        )
    }
}
