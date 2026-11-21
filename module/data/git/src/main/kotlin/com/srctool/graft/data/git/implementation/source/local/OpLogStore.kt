// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.source.local

import com.srctool.graft.data.git.api.models.OperationEntry
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * The op log file (`$GIT_COMMON_DIR/graft/oplog.jsonl`): append-only, capped at [cap] entries. The oldest are dropped
 * the first time a file is written in this process, and again once it has grown [slack] past the cap, so a long session
 * never rewrites the file on every command. Appends must come from one place at a time (the recorder's queue); reads
 * may come from anywhere, since a trim replaces the file in one move.
 */
internal class OpLogStore(private val cap: Int = CAP, private val slack: Int = SLACK) {
    private val counts = ConcurrentHashMap<Path, Int>()

    fun file(commonDir: Path): Path = commonDir.resolve("graft").resolve("oplog.jsonl")

    /** The entries oldest first, at most [cap] of them. A line that can't be read is skipped. */
    fun read(commonDir: Path): List<OperationEntry> = parse(lines(file(commonDir))).first.takeLast(cap)

    /**
     * Adds [entry]. A log written by a newer Graft is read-only here (Storage Formats, "rule 3"): nothing is added.
     * Throws [IOException] when the file can't be written; the caller decides that this never fails the command.
     */
    fun append(commonDir: Path, entry: OperationEntry) {
        val path = file(commonDir)
        Files.createDirectories(path.parent)
        val existing = lines(path)
        val (entries, version) = parse(existing)
        if (version != null && version > OpLogCodec.VERSION) return
        var count = counts[path]
        if (count == null) {
            // First write in this process: the file is opened, so it is trimmed to the cap now.
            count = entries.size
            if (count > cap || (version == null && existing.any { it.isNotBlank() })) {
                rewrite(path, entries.takeLast(cap))
                count = minOf(count, cap)
            }
        } else if (count > cap + slack) {
            rewrite(path, parse(lines(path)).first.takeLast(cap))
            count = cap
        }
        val line = OpLogCodec.encode(entry) + "\n"
        val fresh = !Files.exists(path) || Files.size(path) == 0L
        val text = if (fresh) OpLogCodec.header + "\n" + line else line
        Files.writeString(path, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        counts[path] = count + 1
    }

    private fun lines(path: Path): List<String> = try {
        Files.readAllLines(path, Charsets.UTF_8)
    } catch (_: java.nio.file.NoSuchFileException) {
        emptyList()
    } catch (_: java.nio.charset.CharacterCodingException) {
        // A file that isn't text is corrupt; its readable lines are not worth the guess. Start over from nothing.
        emptyList()
    }

    /** The readable entries, and the header's version when there is one. */
    private fun parse(lines: List<String>): Pair<List<OperationEntry>, Int?> {
        val version = lines.firstOrNull()?.let(OpLogCodec::version)
        return lines.mapNotNull(OpLogCodec::decode) to version
    }

    /** Replaces the file with the header and [entries], by writing beside it and moving it over. */
    private fun rewrite(path: Path, entries: List<OperationEntry>) {
        val temp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(
            temp,
            (listOf(OpLogCodec.header) + entries.map(OpLogCodec::encode)).joinToString("\n", postfix = "\n"),
        )
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val CAP = 1000
        const val SLACK = 100
    }
}
