// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.source.local

import com.srctool.graft.core.git.GitCommand
import com.srctool.graft.data.git.api.models.GuardTarget

/** One path of `git diff --raw -z`. */
internal class RawChange(
    val status: Char,
    val newMode: String,
    val newObject: String,
    val path: String,
    /** The path a rename or copy came from. */
    val oldPath: String?,
)

/** The diff arguments for a [GuardTarget]'s source: the index against `HEAD`, or two revisions. */
internal fun guardSource(target: GuardTarget): List<String> = when (target) {
    is GuardTarget.Range -> listOf(target.from, target.to, "--")
    else -> listOf("--cached", "--")
}

/** The patch to scan: `-U0`, so only the added lines and where they are. */
internal fun guardPatchArgs(target: GuardTarget): List<String> = listOf(
    "diff", "-p", "--no-color", "--no-ext-diff", "--no-textconv", "--src-prefix=a/", "--dst-prefix=b/",
    "--submodule=short", "--unified=0", "-M",
) + guardSource(target)

/** Every changed path with its mode, status and new object, exactly as git names them (`-z`). */
internal class GuardFilesCommand(target: GuardTarget, index: Map<String, String?> = emptyMap()) :
    GitCommand<List<RawChange>>() {
    override val function = "GARD"
    override val args = listOf("diff", "--raw", "-z", "--no-abbrev", "--no-ext-diff", "-M") + guardSource(target)
    override val environment = index

    override fun parse(stdout: ByteArray): List<RawChange> {
        val parts = stdout.toString(Charsets.UTF_8).split('\u0000')
        val out = ArrayList<RawChange>()
        var i = 0
        while (i < parts.size) {
            val head = parts[i]
            if (!head.startsWith(":")) {
                i++
                continue
            }
            val fields = head.substring(1).split(' ')
            val status = fields.getOrNull(4)?.firstOrNull() ?: break
            val paired = status == 'R' || status == 'C'
            val first = parts.getOrNull(i + 1) ?: break
            val second = if (paired) parts.getOrNull(i + 2) ?: break else null
            out += RawChange(status, fields[1], fields[3], second ?: first, if (paired) first else null)
            i += if (paired) 3 else 2
        }
        return out
    }
}

/** Object sizes in bytes for [ids] (written to stdin, one per line); an id git doesn't know is left out. */
internal class ObjectSizesCommand : GitCommand<Map<String, Long>>() {
    override val function = "GARD"
    override val args = listOf("cat-file", "--batch-check=%(objectname) %(objectsize)")

    override fun parse(stdout: ByteArray): Map<String, Long> = stdout.toString(Charsets.UTF_8).lineSequence()
        .mapNotNull { line ->
            val parts = line.split(' ')
            val size = parts.getOrNull(1)?.toLongOrNull()
            if (parts.size == 2 && size != null) parts[0] to size else null
        }
        .toMap()
}

/** Every path in [revision]'s tree, for the case-collision check. */
internal class TreePathsCommand(revision: String) : GitCommand<List<String>>() {
    init {
        require(!revision.startsWith("-")) { "a revision can't start with a dash" }
    }

    override val function = "GARD"
    override val args = listOf("ls-tree", "-r", "--name-only", "-z", revision)

    override fun parse(stdout: ByteArray): List<String> =
        stdout.toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
}
