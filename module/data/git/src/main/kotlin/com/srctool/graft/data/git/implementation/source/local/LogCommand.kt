// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.source.local

import com.srctool.graft.core.git.GitParseException
import com.srctool.graft.core.git.StreamingGitCommand
import com.srctool.graft.data.git.api.models.CommitRow
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.People
import com.srctool.graft.data.git.api.models.Person
import java.io.BufferedInputStream
import java.io.InputStream

/**
 * `git log --topo-order` for the graph (Git Engine note), streamed one [CommitRow] at a time. Names and emails go
 * into [people], which fills up while the rows stream; read it alongside the rows.
 *
 * By default every ref is included except the stash and Graft's own refs: the graph draws stashes, shelves and
 * snapshots as separate nodes, and a local-history chain would otherwise flood the history.
 */
internal class LogCommand(revisions: List<String> = DEFAULT_REVISIONS, public val people: People = People()) :
    StreamingGitCommand<CommitRow>() {
    override val function: String = "LOG"
    override val args: List<String> =
        listOf("log", "--topo-order", "--no-show-signature", "-z", "--format=$FORMAT") + revisions

    override fun parse(stdout: InputStream): Sequence<CommitRow> = LogParser.parse(stdout, people)

    companion object {
        /** Hash, parents, author name, email and time, committer name and time, subject; every field NUL-terminated. */
        const val FORMAT: String = "%H%x00%P%x00%an%x00%ae%x00%at%x00%cn%x00%ct%x00%s%x00"
        val DEFAULT_REVISIONS: List<String> = listOf("--exclude=refs/stash", "--exclude=refs/graft/*", "--all")
    }
}

/**
 * Parses [LogCommand.FORMAT] with `-z`: eight NUL-terminated fields per commit, then the NUL that `-z` puts after each
 * record. Fields may be empty (a root commit's parents, an empty subject), so records are read by count, never by
 * skipping empty fields.
 */
internal object LogParser {
    private const val FIELDS = 8

    fun parse(stdout: InputStream, people: People): Sequence<CommitRow> = sequence {
        val reader = NulReader(stdout)
        val fields = arrayOfNulls<ByteArray>(FIELDS)
        while (true) {
            val first = reader.next() ?: break
            fields[0] = first
            for (i in 1 until FIELDS) {
                fields[i] =
                    reader.next() ?: throw GitParseException("git log record ends after $i of $FIELDS fields")
            }
            val terminator = reader.next()
            if (terminator != null && terminator.isNotEmpty()) {
                throw GitParseException("git log record has more than $FIELDS fields")
            }
            yield(row(fields, people))
        }
    }

    private fun row(f: Array<ByteArray?>, people: People): CommitRow {
        val id = objectId(f[0]!!)
        val parentsText = ascii(f[1]!!)
        val parents = if (parentsText.isEmpty()) emptyList() else parentsText.split(' ').map(::objectId)
        val author = people.intern(Person(GitText.decode(f[2]!!), GitText.decode(f[3]!!)))
        // %cn has no email in the format: the graph shows committers by name only, so the email stays empty.
        val committer = people.intern(Person(GitText.decode(f[5]!!), ""))
        return CommitRow(
            id = id,
            parents = parents,
            authorIndex = author,
            authorTime = time(f[4]!!),
            committerIndex = committer,
            committerTime = time(f[6]!!),
            subject = GitText.decode(f[7]!!),
        )
    }

    private fun ascii(bytes: ByteArray): String = String(bytes, Charsets.ISO_8859_1)

    private fun objectId(bytes: ByteArray): ObjectId = objectId(ascii(bytes))

    private fun objectId(text: String): ObjectId = try {
        ObjectId(text)
    } catch (e: IllegalArgumentException) {
        throw GitParseException("git log printed \"$text\" where an object name was expected")
    }

    private fun time(bytes: ByteArray): Long = ascii(bytes).toLongOrNull()
        ?: throw GitParseException("git log printed \"${ascii(bytes)}\" where a timestamp was expected")
}

/** Reads NUL-terminated tokens from a byte stream. The last token may end at EOF without a NUL. */
internal class NulReader(input: InputStream) {
    private val input = if (input is BufferedInputStream) input else BufferedInputStream(input, 1 shl 16)
    private var buffer = ByteArray(256)

    /** The next token, without its NUL; null at end of stream. */
    fun next(): ByteArray? {
        var size = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (size == 0) null else buffer.copyOf(size)
            if (b == 0) return buffer.copyOf(size)
            if (size == buffer.size) buffer = buffer.copyOf(size * 2)
            buffer[size++] = b.toByte()
        }
    }
}
