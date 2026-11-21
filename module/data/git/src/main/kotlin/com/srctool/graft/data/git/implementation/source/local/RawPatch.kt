// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.source.local

import com.srctool.graft.core.git.GitParseException
import com.srctool.graft.data.git.api.models.FileSelection.Scope
import java.io.ByteArrayOutputStream

/**
 * A unified patch kept as bytes. The viewer's [PatchParser] decodes lines to text, which is right for showing them and
 * wrong for applying them: a Latin-1 line or a CRLF ending must go back to git exactly as it came out.
 */
internal class RawFilePatch(
    val kind: Kind,
    /** The mode of a new or deleted file. */
    val mode: String,
    /** Binary files and submodule changes have no hunks to pick from. */
    val hunks: List<RawHunk>,
) {
    enum class Kind { ADDED, DELETED, MODIFIED }
}

internal class RawHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    /** Everything after the closing `@@` (the function name), as ISO-8859-1 so it survives unchanged. */
    val suffix: String,
    val lines: List<RawLine>,
)

internal class RawLine(
    val kind: Kind,
    /** The line without its marker or its `\n`; a CRLF file's `\r` stays. */
    val content: ByteArray,
    /** `\ No newline at end of file` follows. */
    val noNewlineAtEnd: Boolean,
) {
    enum class Kind { CONTEXT, ADDED, REMOVED }
}

internal object RawPatchParser {
    private val hunkHeader = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(.*)$""")

    /** One [RawFilePatch] per `diff --git` section, in order. */
    fun parse(patch: ByteArray): List<RawFilePatch> {
        val lines = lines(patch)
        val files = ArrayList<RawFilePatch>()
        var i = 0
        while (i < lines.size) {
            if (!patch.startsWith(lines[i], "diff --git ")) {
                i++
                continue
            }
            i++
            var kind = RawFilePatch.Kind.MODIFIED
            var mode = DEFAULT_MODE
            val hunks = ArrayList<RawHunk>()
            while (i < lines.size && !patch.startsWith(lines[i], "diff --git ")) {
                val text = patch.latin1(lines[i])
                when {
                    text.startsWith("new file mode ") -> {
                        kind = RawFilePatch.Kind.ADDED
                        mode = text.removePrefix("new file mode ").trim()
                        i++
                    }

                    text.startsWith("deleted file mode ") -> {
                        kind = RawFilePatch.Kind.DELETED
                        mode = text.removePrefix("deleted file mode ").trim()
                        i++
                    }

                    text.startsWith("@@ ") -> i = hunk(patch, lines, i, hunks)

                    else -> i++ // index, old/new mode, rename, similarity, ---/+++, "Binary files … differ"
                }
            }
            files += RawFilePatch(kind, mode, hunks)
        }
        return files
    }

    /** Reads the hunk at [start], adds it to [into], and returns the index after it. Lines are counted, not guessed. */
    private fun hunk(patch: ByteArray, lines: List<IntRange>, start: Int, into: MutableList<RawHunk>): Int {
        val header = hunkHeader.find(patch.latin1(lines[start])) ?: throw GitParseException("bad hunk header")
        val (os, oc, ns, nc, suffix) = header.destructured
        val oldCount = oc.ifEmpty { "1" }.toInt()
        val newCount = nc.ifEmpty { "1" }.toInt()
        var oldLeft = oldCount
        var newLeft = newCount
        val body = ArrayList<RawLine>()
        var i = start + 1
        while (i < lines.size && (oldLeft > 0 || newLeft > 0 || patch.startsWith(lines[i], "\\"))) {
            val range = lines[i]
            val marker = if (range.isEmpty()) ' ' else patch[range.first].toInt().toChar()
            val content = if (range.isEmpty()) ByteArray(0) else patch.copyOfRange(range.first + 1, range.last + 1)
            when (marker) {
                ' ' -> {
                    body += RawLine(RawLine.Kind.CONTEXT, content, false)
                    oldLeft--
                    newLeft--
                }

                '-' -> {
                    body += RawLine(RawLine.Kind.REMOVED, content, false)
                    oldLeft--
                }

                '+' -> {
                    body += RawLine(RawLine.Kind.ADDED, content, false)
                    newLeft--
                }

                '\\' -> if (body.isNotEmpty()) {
                    val last = body.removeAt(body.lastIndex)
                    body += RawLine(last.kind, last.content, true)
                }

                else -> throw GitParseException("unexpected line in a hunk")
            }
            i++
        }
        if (oldLeft > 0 || newLeft > 0) throw GitParseException("hunk ends early")
        into += RawHunk(os.toInt(), oldCount, ns.toInt(), newCount, suffix, body)
        return i
    }

    /** Line ranges without their `\n`. */
    private fun lines(bytes: ByteArray): List<IntRange> {
        val ranges = ArrayList<IntRange>(bytes.size / 32 + 1)
        var start = 0
        for (i in bytes.indices) {
            if (bytes[i] == NL) {
                ranges += start until i
                start = i + 1
            }
        }
        if (start < bytes.size) ranges += start until bytes.size
        return ranges
    }

    private fun ByteArray.latin1(range: IntRange): String =
        if (range.isEmpty()) "" else String(this, range.first, range.last - range.first + 1, Charsets.ISO_8859_1)

    private fun ByteArray.startsWith(range: IntRange, prefix: String): Boolean {
        if (range.last - range.first + 1 < prefix.length) return false
        for (k in prefix.indices) if (this[range.first + k] != prefix[k].code.toByte()) return false
        return true
    }

    private const val DEFAULT_MODE = "100644"
    private const val NL: Byte = '\n'.code.toByte()
}

/** The selection names a hunk or line the diff doesn't have: the file changed since the user looked. */
internal class SelectionOutOfRange(message: String) : Exception(message)

/** Which side of a diff the target of `git apply` holds. */
internal enum class ApplyTo {
    /** The target is the diff's old side (staging: the index). Unselected additions are left out. */
    OLD,

    /** The target is the diff's new side, and the patch is applied with `-R` (unstaging, discarding). */
    NEW,
}

/**
 * Turns the part of a file's diff the user selected into a patch `git apply` takes on its own (Working Copy and
 * Commit, "Selection model"). Pure: bytes in, bytes out.
 *
 * What is not selected is kept as context where the target has it and left out where it doesn't, so the hunk still
 * matches the target. Counts and start lines are recomputed, because earlier hunks that were left out shift every
 * later one. Mode, rename and similarity lines are not carried: a partial selection changes a file's content only,
 * and the file as a whole goes through `git add` and `git reset`, which move those too.
 *
 * A run of changes that touches a line without a final newline is one unit: selecting any line of it selects all of
 * it. Splitting it would put a new line after a line that has no newline, which no patch can say.
 */
internal object PatchBuilder {
    /**
     * The patch for [scope] of [file] at [path], or null when it selects no change. [applyTo] says which side the
     * patch will meet; for [ApplyTo.NEW] apply it with `-R`. Throws [SelectionOutOfRange] for a hunk or line the
     * diff doesn't have.
     */
    fun build(path: String, file: RawFilePatch, scope: Scope, applyTo: ApplyTo): ByteArray? {
        val selected = select(file, scope)
        if (selected.none { row -> row.any { it } }) return null
        val hunks = ArrayList<EmittedHunk>()
        var shift = 0
        file.hunks.forEachIndexed { index, hunk ->
            if (selected[index].none { it }) return@forEachIndexed
            val emitted = emit(hunk, selected[index], applyTo)
            val oldCount = emitted.count { it.marker != '+' }
            val newCount = emitted.count { it.marker != '-' }
            // Start lines name the first line of a side, or the line before it when that side has none here.
            val oldAt = if (applyTo == ApplyTo.OLD) hunk.oldStart.first(hunk.oldCount) else null
            val newAt = if (applyTo == ApplyTo.NEW) hunk.newStart.first(hunk.newCount) else null
            val oldPos = oldAt ?: (newAt!! - shift)
            val newPos = newAt ?: (oldAt!! + shift)
            shift += newCount - oldCount
            hunks +=
                EmittedHunk(
                    header =
                    "@@ -${oldPos.header(oldCount)}${count(oldCount)} " +
                        "+${newPos.header(newCount)}${count(newCount)} @@${hunk.suffix}",
                    lines = emitted,
                )
        }
        val untouched = hunks.sumOf { h -> h.lines.count { it.marker == ' ' } }
        val form =
            when {
                file.kind == RawFilePatch.Kind.ADDED && (applyTo == ApplyTo.OLD || untouched == 0) -> Form.NEW
                file.kind == RawFilePatch.Kind.DELETED && (applyTo == ApplyTo.NEW || untouched == 0) -> Form.GONE
                else -> Form.CHANGED
            }
        return write(path, file.mode, form, hunks)
    }

    /** `selected[h][l]` is true for a changed line the user picked, after runs touching a missing newline are joined. */
    private fun select(file: RawFilePatch, scope: Scope): Array<BooleanArray> {
        val selected = Array(file.hunks.size) { BooleanArray(file.hunks[it].lines.size) }
        when (scope) {
            Scope.WholeFile -> throw IllegalArgumentException("a whole file is staged by git add, not by a patch")

            is Scope.Hunks -> scope.ids.forEach { id ->
                val hunk = file.hunks.getOrNull(id) ?: throw SelectionOutOfRange("no hunk $id")
                hunk.lines.forEachIndexed { l, line -> if (line.kind != RawLine.Kind.CONTEXT) selected[id][l] = true }
            }

            is Scope.Lines -> scope.ranges.forEach { range ->
                val hunk = file.hunks.getOrNull(range.hunk) ?: throw SelectionOutOfRange("no hunk ${range.hunk}")
                if (range.first > range.last || range.first < 0 || range.last >= hunk.lines.size) {
                    throw SelectionOutOfRange("no lines ${range.first}..${range.last} in hunk ${range.hunk}")
                }
                for (l in range.first..range.last) {
                    if (hunk.lines[l].kind != RawLine.Kind.CONTEXT) selected[range.hunk][l] = true
                }
            }
        }
        file.hunks.forEachIndexed { h, hunk ->
            var l = 0
            while (l < hunk.lines.size) {
                if (hunk.lines[l].kind == RawLine.Kind.CONTEXT) {
                    l++
                    continue
                }
                var end = l
                while (end < hunk.lines.size && hunk.lines[end].kind != RawLine.Kind.CONTEXT) end++
                val run = l until end
                if (run.any { hunk.lines[it].noNewlineAtEnd } && run.any { selected[h][it] }) {
                    run.forEach { selected[h][it] = true }
                }
                l = end
            }
        }
        return selected
    }

    private class Emitted(val marker: Char, val line: RawLine)

    private class EmittedHunk(val header: String, val lines: List<Emitted>)

    private fun emit(hunk: RawHunk, selected: BooleanArray, applyTo: ApplyTo): List<Emitted> {
        val out = ArrayList<Emitted>(hunk.lines.size)
        hunk.lines.forEachIndexed { l, line ->
            when (line.kind) {
                RawLine.Kind.CONTEXT -> out += Emitted(' ', line)

                RawLine.Kind.REMOVED -> when {
                    selected[l] -> out += Emitted('-', line)

                    applyTo == ApplyTo.OLD -> out += Emitted(' ', line)

                    // the target still has it
                    else -> Unit // the target never had it
                }

                RawLine.Kind.ADDED -> when {
                    selected[l] -> out += Emitted('+', line)

                    applyTo == ApplyTo.NEW -> out += Emitted(' ', line)

                    // the target has it
                    else -> Unit
                }
            }
        }
        return out
    }

    private enum class Form { CHANGED, NEW, GONE }

    private fun write(path: String, mode: String, form: Form, hunks: List<EmittedHunk>): ByteArray {
        val out = ByteArrayOutputStream()

        fun text(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        val a = quote("a/$path")
        val b = quote("b/$path")
        text("diff --git $a $b\n")
        when (form) {
            Form.CHANGED -> {
                text("--- ${a.named()}\n+++ ${b.named()}\n")
            }

            Form.NEW -> {
                text("new file mode $mode\n--- /dev/null\n+++ ${b.named()}\n")
            }

            Form.GONE -> {
                text("deleted file mode $mode\n--- ${a.named()}\n+++ /dev/null\n")
            }
        }
        hunks.forEach { hunk ->
            text(hunk.header + "\n")
            hunk.lines.forEach { e ->
                out.write(e.marker.code)
                out.write(e.line.content)
                out.write('\n'.code)
                if (e.line.noNewlineAtEnd) text("\\ No newline at end of file\n")
            }
        }
        return out.toByteArray()
    }

    /** Git's own rule: a name with a space gets a tab after it in `---` and `+++` lines, so the name's end is clear. */
    private fun String.named(): String = if (contains(' ') && !startsWith('"')) "$this\t" else this

    /** C-style quoting, as git writes names with quotes, backslashes, control characters or non-ASCII bytes. */
    private fun quote(name: String): String {
        val bytes = name.toByteArray(Charsets.UTF_8)
        if (bytes.none { it < 0x20 || it == 0x22.toByte() || it == 0x5c.toByte() || it == 0x7f.toByte() || it < 0 }) {
            return name
        }
        val sb = StringBuilder("\"")
        for (byte in bytes) {
            val v = byte.toInt() and 0xff
            when {
                v == '"'.code -> sb.append("\\\"")
                v == '\\'.code -> sb.append("\\\\")
                v == '\n'.code -> sb.append("\\n")
                v == '\t'.code -> sb.append("\\t")
                v < 0x20 || v >= 0x7f -> sb.append('\\').append(v.toString(8).padStart(3, '0'))
                else -> sb.append(v.toChar())
            }
        }
        return sb.append('"').toString()
    }

    /** The first line a side covers: git numbers an empty side by the line before it. */
    private fun Int.first(count: Int): Int = if (count == 0) this + 1 else this

    private fun Int.header(count: Int): Int = if (count == 0) this - 1 else this

    private fun count(n: Int): String = if (n == 1) "" else ",$n"
}
