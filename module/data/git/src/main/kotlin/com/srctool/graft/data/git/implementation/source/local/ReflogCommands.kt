// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.source.local

import com.srctool.graft.core.exception.Redaction
import com.srctool.graft.core.git.GitCommand
import com.srctool.graft.core.git.GitParseException
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.Person
import com.srctool.graft.data.git.api.models.Remote
import com.srctool.graft.data.git.api.models.StashEntry

/** NUL-separated tokens, each decoded on its own (names and messages may be in any encoding). */
private fun tokens(bytes: ByteArray): List<String> {
    val out = ArrayList<String>()
    var start = 0
    for (i in bytes.indices) {
        if (bytes[i] == 0.toByte()) {
            out += GitText.decode(bytes, start, i - start)
            start = i + 1
        }
    }
    if (start < bytes.size) out += GitText.decode(bytes, start, bytes.size - start)
    return out
}

private fun oid(hex: String): ObjectId = try {
    ObjectId(hex)
} catch (e: IllegalArgumentException) {
    throw GitParseException(e.message ?: "bad object id")
}

/**
 * Seconds and zone minutes from a selector printed with `--date=raw`: `HEAD@{1767225600 +0000}`. With a date format,
 * git shows the entry's time instead of its index, so indexes are counted from the page's offset instead.
 */
private fun selectorTime(selector: String): Pair<Long, Int> {
    val inside = selector.substringAfterLast("@{").removeSuffix("}")
    val seconds = inside.substringBefore(' ').toLongOrNull() ?: throw GitParseException("reflog selector \"$selector\"")
    val zone = inside.substringAfter(' ', "+0000")
    val sign = if (zone.startsWith('-')) -1 else 1
    val digits = zone.drop(1).padStart(4, '0')
    return seconds to sign * (digits.take(2).toInt() * 60 + digits.takeLast(2).toInt())
}

/** One reflog entry as `log -g` prints it; the old id comes from the next older entry. */
internal class RawReflogEntry(
    val newId: ObjectId,
    val time: Long,
    val offsetMinutes: Int,
    val message: String,
    val actor: Person,
    val subject: String,
)

/** `log -g` on [ref]: [count] entries after the newest [skip], newest first. */
internal class ReflogCommand(ref: String, skip: Int, count: Int) : GitCommand<List<RawReflogEntry>>() {
    override val function = "RFLG"
    override val args =
        listOf(
            "log",
            "--walk-reflogs",
            "-z",
            "--date=raw",
            "--format=%H%x00%gd%x00%gs%x00%gn%x00%ge%x00%s%x00",
            "--skip=$skip",
            "--max-count=$count",
            ref,
            "--",
        )

    override fun parse(stdout: ByteArray): List<RawReflogEntry> = ReflogParser.parse(stdout)
}

internal object ReflogParser {
    private const val FIELDS = 6

    fun parse(stdout: ByteArray): List<RawReflogEntry> {
        val t = tokens(stdout)
        val entries = ArrayList<RawReflogEntry>()
        var i = 0
        // Six fields, then the empty token `-z` leaves after each record.
        while (i < t.size && t[i].isNotEmpty()) {
            if (i + FIELDS > t.size) throw GitParseException("reflog record ends after ${t.size - i} fields")
            val (time, offset) = selectorTime(t[i + 1])
            entries +=
                RawReflogEntry(
                    newId = oid(t[i]),
                    time = time,
                    offsetMinutes = offset,
                    message = t[i + 2],
                    actor = Person(t[i + 3], t[i + 4]),
                    subject = t[i + 5],
                )
            i += FIELDS
            if (i < t.size && t[i].isEmpty()) i++
        }
        return entries
    }
}

/** `core.logAllRefUpdates`, which exits 1 when it isn't set. */
internal class LogAllRefUpdatesCommand : GitCommand<String?>() {
    override val function = "RFLG"
    override val args = listOf("config", "--get", "core.logAllRefUpdates")
    override val successExitCodes = setOf(0, 1)

    override fun parse(stdout: ByteArray): String? = GitText.decode(stdout).trim().ifEmpty { null }
}

/** `stash list`, newest first: the stash commit, its time, message and parents (base, index, untracked). */
internal class StashListCommand : GitCommand<List<StashEntry>>() {
    override val function = "STSH"
    override val args = listOf("stash", "list", "-z", "--date=raw", "--format=%H%x00%gd%x00%gs%x00%P%x00")

    override fun parse(stdout: ByteArray): List<StashEntry> = StashParser.parse(stdout)
}

internal object StashParser {
    private const val FIELDS = 4
    private val branchOf = Regex("""^(?:WIP on|On) (.+?): """)

    fun parse(stdout: ByteArray): List<StashEntry> {
        val t = tokens(stdout)
        val entries = ArrayList<StashEntry>()
        var i = 0
        while (i < t.size && t[i].isNotEmpty()) {
            if (i + FIELDS > t.size) throw GitParseException("stash record ends after ${t.size - i} fields")
            val message = t[i + 2]
            val branch = branchOf.find(message)?.groupValues?.get(1)?.takeIf { it != "(no branch)" }
            entries +=
                StashEntry(
                    index = entries.size,
                    id = oid(t[i]),
                    base = t[i + 3].split(' ').firstOrNull { it.isNotEmpty() }?.let(::oid),
                    branch = branch,
                    message = message,
                    time = selectorTime(t[i + 1]).first,
                )
            i += FIELDS
            if (i < t.size && t[i].isEmpty()) i++
        }
        return entries
    }
}

/** `remote -v`: `name<TAB>url (fetch)` and `(push)` lines, URLs with `insteadOf` rewrites applied, as git uses them. */
internal class RemotesCommand : GitCommand<List<Remote>>() {
    override val function = "RMTS"
    override val args = listOf("remote", "-v")

    override fun parse(stdout: ByteArray): List<Remote> = RemotesParser.parse(GitText.decode(stdout))
}

internal object RemotesParser {
    private val line = Regex("""^(\S+)\t(.*) \((fetch|push)\)$""")

    fun parse(text: String): List<Remote> {
        val fetch = LinkedHashMap<String, String>()
        val push = LinkedHashMap<String, String>()
        for (raw in text.lines()) {
            if (raw.isBlank()) continue
            val m = line.find(raw) ?: throw GitParseException("remote line \"${maskUrl(raw)}\"")
            val (name, url, kind) = m.destructured
            (if (kind == "fetch") fetch else push)[name] = maskUrl(url)
        }
        return (fetch.keys + push.keys).distinct().map { Remote(it, fetch[it], push[it]) }
    }

    /**
     * Masks credentials in a URL (ADR-7). Over HTTP(S) and other schemes, anything before `@` may be a token, so it's
     * masked whole, as Graft's redaction does everywhere; an SSH URL's user (`ssh://git@host`) is only masked when it
     * carries a password. scp-style addresses (`git@host:org/repo`) have no secret to hide.
     */
    fun maskUrl(url: String): String {
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme == "ssh" || scheme == "git+ssh" || scheme == "ssh+git") {
            val rest = url.substringAfter("://")
            val userInfo = rest.substringBefore('@', "")
            if (':' !in userInfo || '/' in userInfo) return url
            return "$scheme://${userInfo.substringBefore(':')}:${Redaction.MASK}@${rest.substringAfter('@')}"
        }
        return Redaction.redact(url)
    }
}
