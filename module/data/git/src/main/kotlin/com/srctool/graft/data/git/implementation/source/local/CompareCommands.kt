// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.source.local

import com.srctool.graft.core.git.GitCommand
import com.srctool.graft.core.git.GitParseException
import com.srctool.graft.data.git.api.models.AheadBehind
import com.srctool.graft.data.git.api.models.LogQuery
import com.srctool.graft.data.git.api.models.MergePreview
import com.srctool.graft.data.git.api.models.ObjectId

/**
 * The search options before the graph's revisions, and the path after `--`. Text is matched literally (`-F`) and the
 * path as a literal pathspec, so characters like `*` or `[` in what the user typed are never patterns.
 */
internal fun LogQuery.logRevisions(): List<String> = buildList {
    message?.let { add("--grep=$it") }
    author?.let { add("--author=$it") }
    if (message != null || author != null) {
        add("--fixed-strings")
        // Several --grep or an --author: every one must match.
        add("--all-match")
    }
    if (ignoreCase) add("--regexp-ignore-case")
    content?.let { add(if (contentIsRegex) "-G$it" else "-S$it") }
    if (firstParent) add("--first-parent")
    addAll(LogCommand.DEFAULT_REVISIONS)
    add("--")
    path?.let { add(":(literal)$it") }
}

/** `rev-list --left-right --count base...compare`: "only in base" then "only in compare". */
internal class AheadBehindCommand(base: String, compare: String) : GitCommand<AheadBehind>() {
    override val function = "ABHD"
    override val args = listOf("rev-list", "--left-right", "--count", "$base...$compare", "--")

    override fun parse(stdout: ByteArray): AheadBehind {
        val parts = GitText.decode(stdout).trim().split('\t', ' ').filter { it.isNotEmpty() }
        val behind = parts.getOrNull(0)?.toIntOrNull()
        val ahead = parts.getOrNull(1)?.toIntOrNull()
        if (behind == null || ahead == null) throw GitParseException("rev-list counts \"${parts.joinToString(" ")}\"")
        return AheadBehind(ahead, behind)
    }
}

/** `merge-base a b`, which exits 1 with no output when the histories never met. */
internal class OptionalMergeBaseCommand(a: String, b: String) : GitCommand<ObjectId?>() {
    override val function = "MBAS"
    override val args = listOf("merge-base", a, b, "--")
    override val successExitCodes = setOf(0, 1)

    override fun parse(stdout: ByteArray): ObjectId? {
        val text = GitText.decode(stdout).trim()
        if (text.isEmpty()) return null
        return try {
            ObjectId(text)
        } catch (e: IllegalArgumentException) {
            throw GitParseException(e.message ?: "bad merge base")
        }
    }
}

/**
 * `merge-tree --write-tree --name-only -z` (Git 2.38+): exit 0 is a clean merge, exit 1 a conflicted one, both with
 * the resulting tree's id first and, after a conflict, the conflicted paths. It writes the merged tree's objects into
 * the object store, as a merge would; refs, the index and the working tree are untouched.
 */
internal class MergeTreeCommand(into: String, from: String) : GitCommand<MergePreview>() {
    override val function = "MTRE"
    override val args = listOf("merge-tree", "--write-tree", "--name-only", "--no-messages", "-z", into, from)
    override val successExitCodes = setOf(0, 1)

    override fun parse(stdout: ByteArray): MergePreview {
        val parts = ArrayList<String>()
        var start = 0
        for (i in stdout.indices) {
            if (stdout[i] == 0.toByte()) {
                parts += GitText.decode(stdout, start, i - start)
                start = i + 1
            }
        }
        if (start < stdout.size) parts += GitText.decode(stdout, start, stdout.size - start)
        if (parts.isEmpty()) throw GitParseException("merge-tree printed nothing")
        // The first part is the tree; then conflicted paths, ending at the empty part before any messages.
        return MergePreview(parts.drop(1).takeWhile { it.isNotEmpty() }.distinct())
    }
}
