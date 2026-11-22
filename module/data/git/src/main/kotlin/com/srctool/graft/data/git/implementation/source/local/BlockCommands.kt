// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.source.local

import com.srctool.graft.core.git.GitCommand
import com.srctool.graft.data.git.api.models.HunkAnchor
import com.srctool.graft.data.git.api.models.WorkingHunk
import java.security.MessageDigest

/** `HEAD` against the working tree, or (cached) against the index, for the given files (and a renamed file's old path). */
internal class BlockDiffCommand(cached: Boolean, paths: List<String>, contextLines: Int) : GitCommand<ByteArray>() {
    override val function = "BLKS"
    override val args =
        buildList {
            add("diff")
            if (cached) add("--cached") else add("HEAD")
            addAll(listOf("-p", "--no-color", "--no-ext-diff", "--no-textconv", "--src-prefix=a/", "--dst-prefix=b/"))
            addAll(listOf("--submodule=short", "--unified=$contextLines", "-M", "--"))
            addAll(paths)
        }
    override val environment = mapOf("GIT_LITERAL_PATHSPECS" to "1")

    override fun parse(stdout: ByteArray): ByteArray = stdout
}

/** Turns a file's raw hunks into anchored [WorkingHunk]s; bytes are hashed exactly as git printed them. */
internal object HunkAnchors {
    fun of(hunks: List<RawHunk>): List<WorkingHunk> = hunks.mapIndexed { id, hunk ->
        val removed = hunk.lines.filter { it.kind == RawLine.Kind.REMOVED }
        val added = hunk.lines.filter { it.kind == RawLine.Kind.ADDED }
        val old = hunk.lines.filter { it.kind != RawLine.Kind.ADDED }
        val change = digest(removed.map { "-" to it.content } + added.map { "+" to it.content })
        WorkingHunk(
            id,
            HunkAnchor(
                oldStart = hunk.oldStart,
                oldCount = hunk.oldCount,
                change = change,
                removed = digest(removed.map { "-" to it.content }),
                base = digest(old.map { " " to it.content }),
                removedCount = removed.size,
            ),
            additions = added.size,
            deletions = removed.size,
        )
    }

    private fun digest(lines: List<Pair<String, ByteArray>>): String {
        val sha = MessageDigest.getInstance("SHA-1")
        for ((marker, content) in lines) {
            sha.update(marker.toByteArray())
            sha.update(content)
            sha.update('\n'.code.toByte())
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
}
