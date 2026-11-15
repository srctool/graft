// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.source.local

import com.srctool.graft.core.git.GitParseException
import com.srctool.graft.core.git.StreamingGitCommand
import com.srctool.graft.data.git.api.models.AuthorType
import com.srctool.graft.data.git.api.models.CommitRow
import com.srctool.graft.data.git.api.models.LogScope
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
        /**
         * Hash, parents, author name, email and time, committer name and time, subject, and the `Co-authored-by`
         * trailer values separated by 0x1F; every field NUL-terminated.
         */
        const val FORMAT: String =
            "%H%x00%P%x00%an%x00%ae%x00%at%x00%cn%x00%ct%x00%s%x00" +
                "%(trailers:key=Co-authored-by,valueonly=true,separator=%x1f)%x00"
        val DEFAULT_REVISIONS: List<String> = listOf("--exclude=refs/stash", "--exclude=refs/graft/*", "--all")
    }
}

/**
 * [LogCommand]'s revisions for [this] scope. Hidden refs are `--exclude`d before `--all` (git applies an exclude to
 * the next `--all` only); solo refs follow `--end-of-options`, so even a name git would read as an option stays a
 * revision.
 */
internal fun LogScope.revisions(): List<String> = when (this) {
    is LogScope.All -> hidden.sorted().map { "--exclude=$it" } + LogCommand.DEFAULT_REVISIONS
    is LogScope.Only -> listOf("--end-of-options") + refs.sorted()
}

/**
 * Parses [LogCommand.FORMAT] with `-z`: nine NUL-terminated fields per commit, then the NUL that `-z` puts after each
 * record. Fields may be empty (a root commit's parents, an empty subject), so records are read by count, never by
 * skipping empty fields.
 */
internal object LogParser {
    private const val FIELDS = 9

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
        val authorName = GitText.decode(f[2]!!)
        val authorEmail = GitText.decode(f[3]!!)
        val author = people.intern(Person(authorName, authorEmail))
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
            authorType = AuthorTypes.of(authorName, authorEmail, f[8]!!),
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

/**
 * Who wrote a commit, for the author-type filter: a bot by its `[bot]` name or address; AI-assisted when the author
 * or a `Co-authored-by` value is a known assistant, by name or by its no-reply address; otherwise a person.
 */
internal object AuthorTypes {
    private val assistantNames = listOf("claude", "cursor", "copilot")
    private val assistantEmails =
        listOf(
            "noreply@anthropic.com",
            "cursoragent@cursor.com",
            "copilot@github.com",
            "+copilot@users.noreply.github.com",
        )

    fun of(name: String, email: String, coAuthors: ByteArray): AuthorType {
        if (name.endsWith("[bot]", ignoreCase = true) ||
            email.contains("[bot]@", ignoreCase = true)
        ) {
            return AuthorType.BOT
        }
        if (isAssistant(name, email)) return AuthorType.AI_ASSISTED
        if (coAuthors.isEmpty()) return AuthorType.PERSON
        val assisted =
            GitText.decode(coAuthors).split('\u001f').any { value ->
                val coName = value.substringBefore('<').trim()
                val coEmail = value.substringAfter('<', "").substringBefore('>')
                isAssistant(coName, coEmail)
            }
        return if (assisted) AuthorType.AI_ASSISTED else AuthorType.PERSON
    }

    /** "Claude", "Claude Opus 5.5", "Cursor Agent", "Copilot": the first word names the assistant. */
    private fun isAssistant(name: String, email: String): Boolean {
        val firstWord = name.substringBefore(' ').lowercase()
        val address = email.lowercase()
        return firstWord in assistantNames ||
            assistantEmails.any { address == it || (it.startsWith('+') && address.endsWith(it)) }
    }
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
