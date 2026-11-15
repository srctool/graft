// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.source.local

import com.srctool.graft.core.git.GitCommand
import com.srctool.graft.core.git.GitParseException
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.Ref

/**
 * `git for-each-ref` over branches, remote branches, tags, the stash and Graft's refs (Git Engine note), in one call.
 * Ref names can't contain NUL or newline (`git check-ref-format`), so fields are NUL-separated and records end at a
 * newline.
 */
internal class RefsCommand(patterns: List<String> = DEFAULT_PATTERNS) : GitCommand<List<Ref>>() {
    override val function: String = "REFS"
    override val args: List<String> = listOf("for-each-ref", "--format=$FORMAT") + patterns

    override fun parse(stdout: ByteArray): List<Ref> = RefsParser.parse(stdout)

    companion object {
        const val FORMAT: String =
            "%(refname)%00%(objectname)%00%(objecttype)%00%(*objectname)%00%(HEAD)%00" +
                "%(upstream)%00%(upstream:track,nobracket)%00%(symref)"
        val DEFAULT_PATTERNS: List<String> =
            listOf("refs/heads", "refs/remotes", "refs/tags", "refs/stash", "refs/graft")
    }
}

internal object RefsParser {
    private const val FIELDS = 8
    private val ahead = Regex("""\bahead (\d+)""")
    private val behind = Regex("""\bbehind (\d+)""")

    fun parse(stdout: ByteArray): List<Ref> = GitText
        .decode(stdout)
        .split('\n')
        .filter { it.isNotEmpty() }
        .map { line ->
            val f = line.split('\u0000')
            if (f.size != FIELDS) throw GitParseException("for-each-ref printed ${f.size} fields, expected $FIELDS")
            val upstream = f[5].ifEmpty { null }
            val track = f[6]
            val gone = track == "gone"
            Ref(
                name = f[0],
                target = objectId(f[1]),
                peeled = f[3].ifEmpty { null }?.let(::objectId).takeIf { f[2] == "tag" },
                isHead = f[4] == "*",
                upstream = upstream,
                ahead = if (upstream == null ||
                    gone
                ) {
                    null
                } else {
                    ahead.find(track)?.groupValues?.get(1)?.toInt() ?: 0
                },
                behind = if (upstream == null ||
                    gone
                ) {
                    null
                } else {
                    behind.find(track)?.groupValues?.get(1)?.toInt() ?: 0
                },
                upstreamGone = gone,
                symbolicTarget = f[7].ifEmpty { null },
            )
        }

    private fun objectId(text: String): ObjectId = try {
        ObjectId(text)
    } catch (_: IllegalArgumentException) {
        throw GitParseException("for-each-ref printed \"$text\" where an object name was expected")
    }
}
