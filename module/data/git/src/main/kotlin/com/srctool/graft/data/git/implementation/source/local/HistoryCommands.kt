// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.source.local

import com.srctool.graft.core.git.GitCommand
import com.srctool.graft.core.git.GitParseException
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.Snapshot
import java.nio.file.Path

/** Where a worktree's history lives. */
internal class HistoryLocation(val gitDir: Path, val commonDir: Path) {
    /** The main worktree is `main`; a linked one is `wt-<its folder under worktrees/>`, so no name can collide. */
    val worktreeId: String =
        if (gitDir == commonDir) "main" else "wt-${gitDir.fileName}"
    val ref: String = "$HISTORY/$worktreeId"
    val rewriteRef: String = "refs/graft/history-rewrite/$worktreeId"

    companion object {
        const val HISTORY = "refs/graft/history"
    }
}

/** This worktree's git dir and the one shared by all worktrees, as absolute paths. */
internal class LocateHistoryCommand(override val function: String = "LHST") : GitCommand<HistoryLocation>() {
    override val args = listOf("rev-parse", "--path-format=absolute", "--absolute-git-dir", "--git-common-dir")

    override fun parse(stdout: ByteArray): HistoryLocation {
        val lines = GitText.decode(stdout).lines().filter { it.isNotEmpty() }
        if (lines.size != 2) throw GitParseException("rev-parse gave ${lines.size} lines, expected 2")
        return HistoryLocation(Path.of(lines[0]), Path.of(lines[1]))
    }
}

/** Files that differ from the user's index, or that it doesn't know and no ignore rule covers. */
internal class SnapshotCandidatesCommand : GitCommand<Set<String>>() {
    override val function = "LSF"
    override val args = listOf("ls-files", "-z", "-m", "-o", "--exclude-standard")

    override fun parse(stdout: ByteArray): Set<String> =
        GitText.decode(stdout).split('\u0000').filter { it.isNotEmpty() }.toSet()
}

/** Records the files' present state in the index named by [environment]: added, changed or removed. Paths on stdin. */
internal class UpdateIndexCommand(override val environment: Map<String, String?>) : GitCommand<Unit>() {
    override val function = "UIDX"
    override val args = listOf("update-index", "--add", "--remove", "-z", "--stdin")

    override fun parse(stdout: ByteArray) = Unit
}

/** Takes one path out of the index named by [environment]. The path is on stdin. */
internal class ForgetPathCommand(override val environment: Map<String, String?>) : GitCommand<Unit>() {
    override val function = "UIDX"
    override val args = listOf("update-index", "--force-remove", "-z", "--stdin")

    override fun parse(stdout: ByteArray) = Unit
}

/** Fills the index named by [environment] from [tree]. */
internal class ReadTreeCommand(tree: ObjectId, override val environment: Map<String, String?>) : GitCommand<Unit>() {
    override val function = "RTRE"
    override val args = listOf("read-tree", tree.hex)

    override fun parse(stdout: ByteArray) = Unit
}

internal class WriteTreeCommand(override val environment: Map<String, String?>) : GitCommand<ObjectId>() {
    override val function = "WTRE"
    override val args = listOf("write-tree")

    override fun parse(stdout: ByteArray): ObjectId = objectId(GitText.decode(stdout).trim())
}

/** [revision]'s object, or null when there is none (`rev-parse --verify` exits 1). */
internal class ResolveCommand(revision: String) : GitCommand<ObjectId?>() {
    override val function = "RPRS"
    override val args = listOf("rev-parse", "--verify", "--quiet", "$revision^{object}")
    override val successExitCodes = setOf(0, 1)

    override fun parse(stdout: ByteArray): ObjectId? =
        GitText.decode(stdout).trim().takeIf { it.isNotEmpty() }?.let(::objectId)
}

/** The fixed identity every snapshot carries, so history never holds the user's name or email (Storage Formats). */
private fun graftIdentity(time: Long): Map<String, String?> {
    val date = "$time +0000"
    return mapOf(
        "GIT_AUTHOR_NAME" to "Graft",
        "GIT_AUTHOR_EMAIL" to "graft@localhost",
        "GIT_AUTHOR_DATE" to date,
        "GIT_COMMITTER_NAME" to "Graft",
        "GIT_COMMITTER_EMAIL" to "graft@localhost",
        "GIT_COMMITTER_DATE" to date,
    )
}

/** A commit of [tree] on top of [parent], with the message on stdin. Signing is off: these are not the user's commits. */
internal class CommitTreeCommand(tree: ObjectId, parent: ObjectId?, time: Long) : GitCommand<ObjectId>() {
    override val function = "CTRE"
    override val args =
        buildList {
            addAll(listOf("-c", "commit.gpgsign=false", "commit-tree", tree.hex))
            parent?.let { addAll(listOf("-p", it.hex)) }
            addAll(listOf("-F", "-"))
        }
    override val environment = graftIdentity(time)

    override fun parse(stdout: ByteArray): ObjectId = objectId(GitText.decode(stdout).trim())
}

/** Moves [ref] to [new] only if it is still [old] (null: only if it does not exist), as Storage Formats says. */
internal class UpdateRefCommand(ref: String, new: ObjectId, old: ObjectId?) : GitCommand<Unit>() {
    override val function = "UREF"
    override val mutates = true
    override val args = listOf("update-ref", ref, new.hex, old?.hex.orEmpty())

    override fun parse(stdout: ByteArray) = Unit
}

internal class DeleteRefCommand(ref: String) : GitCommand<Unit>() {
    override val function = "UREF"
    override val mutates = true
    override val args = listOf("update-ref", "-d", ref)

    override fun parse(stdout: ByteArray) = Unit
}

private const val RECORD = '\u001e'
private const val FIELD = '\u001f'

/** The history chain, newest first, with each snapshot's tree and full message. */
internal class SnapshotLogCommand(ref: String, limit: Int) : GitCommand<List<Snapshot>>() {
    override val function = "LLOG"
    override val args =
        buildList {
            addAll(listOf("log", "--no-show-signature", "--format=%x1e%H%x1f%P%x1f%ct%x1f%T%x1f%B"))
            if (limit != Int.MAX_VALUE) add("--max-count=$limit")
            addAll(listOf(ref, "--"))
        }
    override val successExitCodes = setOf(0)

    override fun parse(stdout: ByteArray): List<Snapshot> = GitText.decode(stdout).split(RECORD).drop(1).map { record ->
        val fields = record.split(FIELD, limit = 5)
        if (fields.size != 5) throw GitParseException("snapshot record has ${fields.size} fields")
        val message = fields[4].trimEnd('\n')
        Snapshot(
            id = objectId(fields[0]),
            parent = fields[1].trim().takeIf { it.isNotEmpty() }?.let { objectId(it.substringBefore(' ')) },
            time = fields[2].toLongOrNull() ?: throw GitParseException("snapshot time \"${fields[2]}\""),
            label = message.lines().firstOrNull { it.startsWith(LABEL_TRAILER) }?.removePrefix(LABEL_TRAILER),
            tree = objectId(fields[3]),
            message = message,
        )
    }

    companion object {
        const val LABEL_TRAILER = "Graft-Label: "
    }
}

/** For a path, the snapshots that changed it: the raw record's new object, all zeros when it was removed. */
internal class FileVersionsCommand(ref: String, path: String) : GitCommand<List<Pair<ObjectId, ObjectId?>>>() {
    override val function = "LVER"
    override val args =
        listOf("log", "--format=%x1e%H", "--raw", "--no-abbrev", "--no-renames", ref, "--", path)
    override val environment = mapOf("GIT_LITERAL_PATHSPECS" to "1")

    override fun parse(stdout: ByteArray): List<Pair<ObjectId, ObjectId?>> {
        val out = ArrayList<Pair<ObjectId, ObjectId?>>()
        GitText.decode(stdout).split(RECORD).drop(1).forEach { record ->
            val lines = record.lines()
            val snapshot = objectId(lines.first().trim())
            // ":100644 100644 <old> <new> M<TAB>path"; only the metadata before the tab is read.
            val raw = lines.firstOrNull { it.startsWith(":") } ?: return@forEach
            val fields = raw.substringBefore('\t').removePrefix(":").split(' ')
            if (fields.size < 5) throw GitParseException("raw record \"$raw\"")
            out += snapshot to fields[3].takeUnless { id -> id.all { it == '0' } }?.let(::objectId)
        }
        return out
    }
}

/** Snapshots with the paths a log search matched in them; [filter] is the search (`-S<text>`) or none. */
internal class SnapshotPathsCommand(ref: String, filter: String?) : GitCommand<List<Pair<ObjectId, List<String>>>>() {
    override val function = "LSRC"
    override val args =
        buildList {
            addAll(listOf("log", "-z", "--format=%x1e%H", "--name-only", "--no-renames"))
            filter?.let { add("-S$it") }
            addAll(listOf(ref, "--"))
        }

    override fun parse(stdout: ByteArray): List<Pair<ObjectId, List<String>>> {
        val out = ArrayList<Pair<ObjectId, List<String>>>()
        var paths: MutableList<String>? = null
        var id: ObjectId? = null
        fun close() {
            id?.let { out += it to paths.orEmpty() }
        }
        // `log -z` ends each record with NUL and puts one NUL between names, so a record's name list is what follows
        // its id until the next one that starts with the record mark.
        GitText.decode(stdout).split('\u0000').forEach { token ->
            when {
                token.startsWith(RECORD) -> {
                    close()
                    id = objectId(token.drop(1).trim())
                    paths = ArrayList()
                }

                token.isNotEmpty() -> paths?.add(token.trimStart('\n'))
            }
        }
        close()
        return out
    }
}

/** Every path each snapshot changed, newest first, with git's letter for how (`A`, `M`, `D`, `T`). */
internal class SnapshotStatusCommand(ref: String) : GitCommand<List<Pair<ObjectId, List<Pair<Char, String>>>>>() {
    override val function = "LSTA"
    override val args =
        listOf("log", "-z", "--root", "--format=%x1e%H", "--name-status", "--no-renames", ref, "--")

    override fun parse(stdout: ByteArray): List<Pair<ObjectId, List<Pair<Char, String>>>> {
        val out = ArrayList<Pair<ObjectId, MutableList<Pair<Char, String>>>>()
        var status: Char? = null
        // After a record's id the NUL-separated tokens alternate: a status letter, then its path.
        GitText.decode(stdout).split('\u0000').forEach { token ->
            val letter = status
            when {
                letter != null -> {
                    out.lastOrNull()?.second?.add(letter to token)
                    status = null
                }

                token.startsWith(RECORD) -> out += objectId(token.drop(1).trim()) to ArrayList()

                token.isNotBlank() -> status = token.trim().first()
            }
        }
        return out
    }
}

/** Paths [from] has that [to] lacks (`--diff-filter=D` when [from] is the older tree). */
internal class MissingPathsCommand(from: ObjectId, to: ObjectId, filter: String) : GitCommand<List<String>>() {
    override val function = "DTRE"
    override val args =
        listOf("diff-tree", "-r", "-z", "--name-only", "--no-renames", "--diff-filter=$filter", from.hex, to.hex)

    override fun parse(stdout: ByteArray): List<String> =
        GitText.decode(stdout).split('\u0000').filter { it.isNotEmpty() }
}

/** Puts files back as [source] had them, in the working tree only. Paths on stdin, literal. */
internal class RestoreFromCommand(source: ObjectId) : GitCommand<Unit>() {
    override val function = "REST"
    override val mutates = true
    override val args =
        listOf("restore", "--source=${source.hex}", "--worktree", "--pathspec-from-file=-", "--pathspec-file-nul")
    override val environment = mapOf("GIT_LITERAL_PATHSPECS" to "1")

    override fun parse(stdout: ByteArray) = Unit
}

/**
 * The diff from [snapshot]'s file to the working file, read through a scratch index holding that file as it is now:
 * against the user's index a file git doesn't track would read as deleted.
 */
internal class SnapshotDiffCommand(snapshot: ObjectId, path: String, contextLines: Int, index: Map<String, String?>) :
    GitCommand<ByteArray>() {
    override val function = "DIFF"
    override val args =
        listOf(
            "diff", "--cached", snapshot.hex, "-p", "--no-color", "--no-ext-diff", "--no-textconv", "--src-prefix=a/",
            "--dst-prefix=b/", "--no-renames", "--unified=$contextLines", "--", path,
        )
    override val environment = index + ("GIT_LITERAL_PATHSPECS" to "1")

    override fun parse(stdout: ByteArray): ByteArray = stdout
}

/** `git fast-import` to write a rewritten chain in one process; the commands are on stdin. */
internal class FastImportCommand : GitCommand<Unit>() {
    override val function = "FIMP"
    override val mutates = true
    override val args = listOf("fast-import", "--quiet", "--date-format=raw")

    override fun parse(stdout: ByteArray) = Unit
}

/** Bytes of every object [ref] keeps that no branch, tag or remote branch has: what the history costs. */
internal class HistoryDiskUsageCommand(ref: String) : GitCommand<Long>() {
    override val function = "RLST"
    override val args =
        listOf("rev-list", "--objects", "--disk-usage", ref, "--not", "--branches", "--tags", "--remotes")

    override fun parse(stdout: ByteArray): Long = GitText.decode(stdout).trim().toLongOrNull()
        ?: throw GitParseException("disk usage \"${GitText.decode(stdout).trim()}\"")
}

/** Every history ref with the time of its newest snapshot. */
internal class HistoryRefsCommand : GitCommand<List<Pair<String, Long>>>() {
    override val function = "FREF"
    override val args =
        listOf("for-each-ref", "--format=%(refname) %(committerdate:unix)", "${HistoryLocation.HISTORY}/")

    override fun parse(stdout: ByteArray): List<Pair<String, Long>> =
        GitText.decode(stdout).lines().filter { it.isNotBlank() }.map {
            val (name, time) = it.trim().split(' ')
            name to (time.toLongOrNull() ?: throw GitParseException("ref time \"$time\""))
        }
}

private fun objectId(hex: String): ObjectId = try {
    ObjectId(hex)
} catch (e: IllegalArgumentException) {
    throw GitParseException(e.message ?: "bad object id")
}
