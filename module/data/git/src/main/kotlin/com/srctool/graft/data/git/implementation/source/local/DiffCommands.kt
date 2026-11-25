// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.source.local

import com.srctool.graft.core.git.GitCommand
import com.srctool.graft.core.git.GitParseException
import com.srctool.graft.data.git.api.models.ChangedFile
import com.srctool.graft.data.git.api.models.CommitDetails
import com.srctool.graft.data.git.api.models.DiffLine
import com.srctool.graft.data.git.api.models.DiffOptions
import com.srctool.graft.data.git.api.models.DiffTarget
import com.srctool.graft.data.git.api.models.FileDiff
import com.srctool.graft.data.git.api.models.FileStatus
import com.srctool.graft.data.git.api.models.Hunk
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.Signature
import com.srctool.graft.data.git.api.models.SignatureStatus
import com.srctool.graft.data.git.api.models.Trailer

/**
 * `diff-tree` for [target], with [options] before the revisions and [paths] after `--`. Always `diff-tree`, never
 * `diff`: outside a repository `git diff A B` silently turns into `--no-index` and compares two file system paths.
 * A single commit gets `--root` (a root commit against nothing) and its first parent for a merge. [DiffTarget.Since]
 * must be resolved to its merge base first ([MergeBaseCommand]).
 */
private fun diffArgs(target: DiffTarget, options: List<String>, paths: List<String>): List<String> {
    if (target == DiffTarget.Staged || target == DiffTarget.Unstaged) {
        // `git diff`, not `diff-tree`: the working tree isn't a tree. The caller checks it's inside a repository first,
        // since outside one `git diff` turns into `--no-index`.
        val cached = if (target == DiffTarget.Staged) listOf("--cached") else emptyList()
        return listOf("diff") + cached + options + "--" + paths
    }
    val revisions =
        when (target) {
            is DiffTarget.Commit -> listOf("--root", "--diff-merges=first-parent", target.id.hex)

            is DiffTarget.Between -> listOf(target.from, target.to)

            is DiffTarget.Since -> throw IllegalArgumentException(
                "resolve ${target.base}...${target.tip} to its merge base first",
            )

            DiffTarget.Staged, DiffTarget.Unstaged -> error("handled above")
        }
    return listOf("diff-tree", "-r", "--no-commit-id") + options + revisions + "--" + paths
}

/** Where [base] and [tip] forked: `A...B` means "from here to [tip]". */
internal class MergeBaseCommand(base: String, tip: String) : GitCommand<ObjectId>() {
    override val function = "MBAS"
    override val args = listOf("merge-base", base, tip, "--")

    override fun parse(stdout: ByteArray): ObjectId = try {
        ObjectId(GitText.decode(stdout).trim())
    } catch (e: IllegalArgumentException) {
        throw GitParseException(e.message ?: "bad merge base")
    }
}

private fun DiffOptions.flags(): List<String> = buildList {
    add(if (detectRenames) "-M" else "--no-renames")
    if (ignoreWhitespace) add("-w")
}

/** The changed files of [target]: `--raw --numstat -z`, so paths are exact whatever bytes they hold. */
internal class ChangedFilesCommand(
    target: DiffTarget,
    options: DiffOptions = DiffOptions(),
    paths: List<String> = emptyList(),
) : GitCommand<List<ChangedFile>>() {
    override val function = "FILS"
    override val args = diffArgs(target, listOf("-z", "--no-abbrev", "--raw", "--numstat") + options.flags(), paths)

    override fun parse(stdout: ByteArray): List<ChangedFile> = ChangedFilesParser.parse(stdout)
}

/** The unified patch of [target]. Prefixes, colors and external tools are fixed, so user config can't change it. */
internal class PatchCommand(
    target: DiffTarget,
    options: DiffOptions = DiffOptions(),
    paths: List<String> = emptyList(),
) : GitCommand<ByteArray>() {
    override val function = "DIFF"
    override val args =
        diffArgs(
            target,
            listOf(
                "-p",
                "--no-color",
                "--no-ext-diff",
                "--no-textconv",
                "--src-prefix=a/",
                "--dst-prefix=b/",
                "--submodule=short",
                "--unified=${options.contextLines}",
            ) + options.flags(),
            paths,
        )

    override fun parse(stdout: ByteArray): ByteArray = stdout
}

/** Splits [bytes] at NULs into (start, length) ranges, so each part is decoded on its own. */
private fun nulRanges(bytes: ByteArray): List<IntRange> {
    val ranges = ArrayList<IntRange>()
    var start = 0
    for (i in bytes.indices) {
        if (bytes[i] == 0.toByte()) {
            ranges += start until i
            start = i + 1
        }
    }
    if (start < bytes.size) ranges += start until bytes.size
    return ranges
}

private fun ByteArray.text(range: IntRange): String = GitText.decode(this, range.first, range.last - range.first + 1)

internal object ChangedFilesParser {
    private class Raw(
        val oldMode: String,
        val newMode: String,
        val oldObject: ObjectId?,
        val newObject: ObjectId?,
        val status: FileStatus,
        val score: Int?,
        val path: String,
        val oldPath: String?,
    )

    fun parse(bytes: ByteArray): List<ChangedFile> {
        val parts = nulRanges(bytes)
        val raws = ArrayList<Raw>()
        var i = 0
        // Raw records first: ":oldmode newmode oldsha newsha STATUS", then one path, or two for a rename or copy.
        while (i < parts.size && bytes.text(parts[i]).startsWith(":")) {
            val meta = bytes.text(parts[i]).removePrefix(":").split(' ')
            if (meta.size != 5) throw GitParseException("raw record \"${meta.joinToString(" ")}\"")
            val letter = meta[4].firstOrNull() ?: throw GitParseException("raw record without a status")
            val status = statusOf(letter)
            val score = meta[4].drop(1).toIntOrNull()
            val twoPaths = letter == 'R' || letter == 'C'
            val first = parts.getOrNull(i + 1)?.let(bytes::text) ?: throw GitParseException("raw record without a path")
            if (twoPaths) {
                val second =
                    parts.getOrNull(i + 2)?.let(bytes::text) ?: throw GitParseException("rename without a new path")
                raws += Raw(meta[0], meta[1], objectOf(meta[2]), objectOf(meta[3]), status, score, second, first)
                i += 3
            } else {
                raws += Raw(meta[0], meta[1], objectOf(meta[2]), objectOf(meta[3]), status, score, first, null)
                i += 2
            }
        }
        // Then numstat records in the same order: "added\tdeleted\tpath", or "added\tdeleted\t" plus two paths.
        return raws.map { raw ->
            val stat =
                parts.getOrNull(i)?.let(bytes::text) ?: throw GitParseException("missing numstat for ${raw.path}")
            val fields = stat.split('\t')
            if (fields.size < 3) throw GitParseException("numstat record \"$stat\"")
            i += if (fields[2].isEmpty()) 3 else 1
            ChangedFile(
                status = raw.status,
                path = raw.path,
                oldPath = raw.oldPath,
                similarity = raw.score.takeIf { raw.oldPath != null },
                oldMode = raw.oldMode,
                newMode = raw.newMode,
                additions = fields[0].toIntOrNull(),
                deletions = fields[1].toIntOrNull(),
                oldObject = raw.oldObject,
                newObject = raw.newObject,
            )
        }
    }

    /** An object id, or null for git's all-zero "no object". */
    private fun objectOf(hex: String): ObjectId? = if (hex.all { it == '0' }) {
        null
    } else {
        try {
            ObjectId(hex)
        } catch (e: IllegalArgumentException) {
            throw GitParseException(e.message ?: "bad object id")
        }
    }

    private fun statusOf(letter: Char): FileStatus = when (letter) {
        'A' -> FileStatus.ADDED
        'C' -> FileStatus.COPIED
        'D' -> FileStatus.DELETED
        'M' -> FileStatus.MODIFIED
        'R' -> FileStatus.RENAMED
        'T' -> FileStatus.TYPE_CHANGED
        'U' -> FileStatus.UNMERGED
        else -> FileStatus.UNKNOWN
    }
}

/**
 * A unified patch into hunks and numbered lines. Each `diff --git` section belongs to the file at the same position in
 * [files] (git writes both in one order), so paths are never read back from headers, which git may quote. Hunk lines
 * are counted against the `@@` header, so a line that happens to look like a header is never mistaken for one.
 */
internal object PatchParser {
    private val hunkHeader = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@ ?(.*)$""")
    private val sectionStart = "diff --git ".toByteArray()

    fun parse(patch: ByteArray, files: List<ChangedFile>): List<FileDiff> {
        val lines = lineRanges(patch)
        val sections = ArrayList<MutableList<Hunk>>()
        var i = 0
        while (i < lines.size) {
            val range = lines[i]
            if (patch.startsWith(range, sectionStart)) {
                sections.add(ArrayList())
                i++
                continue
            }
            val header = if (patch.startsWith(range, "@@ ".toByteArray())) hunkHeader.find(patch.text(range)) else null
            if (header == null || sections.isEmpty()) {
                i++ // index, mode, rename, "Binary files … differ", ---/+++ lines
                continue
            }
            val (oldStart, oldCount, newStart, newCount, section) = header.destructured
            val hunkLines = ArrayList<DiffLine>()
            var oldLeft = oldCount.ifEmpty { "1" }.toInt()
            var newLeft = newCount.ifEmpty { "1" }.toInt()
            var oldNumber = oldStart.toInt()
            var newNumber = newStart.toInt()
            i++
            while (i < lines.size && (oldLeft > 0 || newLeft > 0 || patch.startsWith(lines[i], "\\".toByteArray()))) {
                val line = lines[i]
                val text = if (line.isEmpty()) "" else patch.text(line.first + 1..line.last)
                when (if (line.isEmpty()) ' ' else patch[line.first].toInt().toChar()) {
                    ' ' -> {
                        hunkLines += DiffLine(DiffLine.Kind.CONTEXT, oldNumber++, newNumber++, text)
                        oldLeft--
                        newLeft--
                    }

                    '-' -> {
                        hunkLines += DiffLine(DiffLine.Kind.REMOVED, oldNumber++, null, text)
                        oldLeft--
                    }

                    '+' -> {
                        hunkLines += DiffLine(DiffLine.Kind.ADDED, null, newNumber++, text)
                        newLeft--
                    }

                    '\\' -> if (hunkLines.isNotEmpty()) {
                        hunkLines[hunkLines.lastIndex] = hunkLines.last().copy(noNewlineAtEnd = true)
                    }

                    else -> throw GitParseException("unexpected line in a hunk: \"${patch.text(line)}\"")
                }
                i++
            }
            if (oldLeft > 0 || newLeft > 0) throw GitParseException("hunk ends early")
            sections.last() +=
                Hunk(
                    oldStart = oldStart.toInt(),
                    oldCount = oldCount.ifEmpty { "1" }.toInt(),
                    newStart = newStart.toInt(),
                    newCount = newCount.ifEmpty { "1" }.toInt(),
                    section = section,
                    lines = hunkLines,
                )
        }
        if (sections.size != files.size) {
            throw GitParseException("the patch has ${sections.size} files, the file list ${files.size}")
        }
        return files.mapIndexed { n, file -> FileDiff(file, sections[n]) }
    }

    /** Line ranges without their `\n` (an empty line is an empty range). */
    private fun lineRanges(bytes: ByteArray): List<IntRange> {
        val ranges = ArrayList<IntRange>(bytes.size / 32)
        var start = 0
        for (i in bytes.indices) {
            if (bytes[i] == '\n'.code.toByte()) {
                ranges += start until i
                start = i + 1
            }
        }
        if (start < bytes.size) ranges += start until bytes.size
        return ranges
    }

    private fun ByteArray.startsWith(range: IntRange, prefix: ByteArray): Boolean {
        if (range.isEmpty() || range.last - range.first + 1 < prefix.size) return false
        for (k in prefix.indices) if (this[range.first + k] != prefix[k]) return false
        return true
    }
}

/** A commit's metadata: `log -1` with NUL-separated fields, raw dates, `%G?` and the trailers. */
internal class DetailsCommand(id: ObjectId) : GitCommand<DetailsParser.Metadata>() {
    override val function = "DETL"
    override val args =
        listOf(
            "log",
            "-1",
            "--no-show-signature",
            "--date=raw",
            "--format=%H%x00%P%x00%an%x00%ae%x00%ad%x00%cn%x00%ce%x00%cd%x00%G?%x00" +
                "%(trailers:only=true,unfold=true,separator=%x1e,key_value_separator=%x1f)%x00%B",
            id.hex,
            "--",
        )

    override fun parse(stdout: ByteArray): DetailsParser.Metadata = DetailsParser.parse(stdout)
}

internal object DetailsParser {
    /** [CommitDetails] without its files, which come from another command. */
    class Metadata(
        val id: ObjectId,
        val parents: List<ObjectId>,
        val author: Signature,
        val committer: Signature,
        val signature: SignatureStatus,
        val trailers: List<Trailer>,
        val message: String,
    ) {
        fun withFiles(files: List<ChangedFile>) =
            CommitDetails(id, parents, author, committer, message, trailers, signature, files)
    }

    fun parse(bytes: ByteArray): Metadata {
        val parts = nulRanges(bytes)
        if (parts.size < 11) throw GitParseException("commit details have ${parts.size} fields, expected 11")
        val f = parts.take(10).map(bytes::text)
        // The message is the rest, NULs and all (a message can't contain one, but nothing is dropped if it does).
        val message = bytes.text(parts[10].first until bytes.size).trimEnd('\n')
        return Metadata(
            id = oid(f[0]),
            parents = f[1].split(' ').filter { it.isNotEmpty() }.map(::oid),
            author = signature(f[2], f[3], f[4]),
            committer = signature(f[5], f[6], f[7]),
            signature = signatureStatus(f[8]),
            trailers =
            f[9].split('\u001e').filter { it.isNotEmpty() }.map {
                Trailer(it.substringBefore('\u001f'), it.substringAfter('\u001f', ""))
            },
            message = message,
        )
    }

    private fun signature(name: String, email: String, raw: String): Signature {
        val time = raw.substringBefore(' ').toLongOrNull() ?: throw GitParseException("date \"$raw\"")
        val zone = raw.substringAfter(' ', "+0000")
        val sign = if (zone.startsWith('-')) -1 else 1
        val digits = zone.drop(1).padStart(4, '0')
        val offset = sign * (digits.take(2).toInt() * 60 + digits.takeLast(2).toInt())
        return Signature(name, email, time, offset)
    }

    private fun signatureStatus(code: String): SignatureStatus = when (code) {
        "G" -> SignatureStatus.GOOD
        "U" -> SignatureStatus.GOOD_UNKNOWN_VALIDITY
        "X" -> SignatureStatus.EXPIRED_SIGNATURE
        "Y" -> SignatureStatus.EXPIRED_KEY
        "R" -> SignatureStatus.REVOKED_KEY
        "B" -> SignatureStatus.BAD
        "E" -> SignatureStatus.CANNOT_CHECK
        else -> SignatureStatus.NONE
    }

    private fun oid(hex: String): ObjectId = try {
        ObjectId(hex)
    } catch (e: IllegalArgumentException) {
        throw GitParseException(e.message ?: "bad object id")
    }
}
