// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.source.local

import com.srctool.graft.core.git.GitCommand
import com.srctool.graft.core.git.GitOutput
import com.srctool.graft.core.git.GitParseException
import com.srctool.graft.core.git.StreamingGitCommand
import com.srctool.graft.data.git.api.models.CloneProgress
import com.srctool.graft.data.git.api.models.CloneRequest
import com.srctool.graft.data.git.api.models.Head
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.RepositorySummary
import com.srctool.graft.data.git.api.models.Upstream
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.Paths

/** What `rev-parse` says about a folder: its git dirs, and whether it's bare or inside `.git`. */
internal data class Located(val gitDir: Path, val commonDir: Path, val bare: Boolean, val insideGitDir: Boolean)

/**
 * `rev-parse` for the git dirs. `--show-toplevel` is asked separately ([TopLevelCommand]) because it fails in a bare
 * repository and inside `.git`. rev-parse has no `-z`; its paths end at a newline, which a folder name can't contain on
 * the file systems Graft supports in practice.
 */
internal class LocateCommand : GitCommand<Located>() {
    override val function = "OPEN"
    override val args =
        listOf(
            "rev-parse",
            "--path-format=absolute",
            "--git-dir",
            "--git-common-dir",
            "--is-bare-repository",
            "--is-inside-git-dir",
        )

    override fun parse(stdout: ByteArray): Located {
        val lines = GitText.decode(stdout).lines().filter { it.isNotEmpty() }
        if (lines.size < 4) throw GitParseException("rev-parse gave ${lines.size} lines, expected 4")
        return Located(Paths.get(lines[0]), Paths.get(lines[1]), lines[2].toFlag(), lines[3].toFlag())
    }

    private fun String.toFlag(): Boolean = when (this) {
        "true" -> true
        "false" -> false
        else -> throw GitParseException("rev-parse flag \"$this\"")
    }
}

/** The top of the working tree. */
internal class TopLevelCommand : GitCommand<Path>() {
    override val function = "OPEN"
    override val args = listOf("rev-parse", "--path-format=absolute", "--show-toplevel")

    override fun parse(stdout: ByteArray): Path =
        Paths.get(GitText.decode(stdout).trimEnd('\n').ifEmpty { throw GitParseException("no top level") })
}

/**
 * `status --porcelain=v2 --branch -z`: the `# branch.*` headers give HEAD and the upstream, and any entry after them
 * means the repository is dirty (untracked files count, ignored ones don't).
 */
internal class StatusSummaryCommand : GitCommand<RepositorySummary>() {
    override val function = "STAT"
    override val args = listOf("status", "--porcelain=v2", "--branch", "-z", "--untracked-files=normal")

    override fun parse(stdout: ByteArray): RepositorySummary = StatusSummaryParser.parse(stdout)
}

internal object StatusSummaryParser {
    fun parse(stdout: ByteArray): RepositorySummary {
        var oid: String? = null
        var head: String? = null
        var upstream: String? = null
        var ab: Pair<Int, Int>? = null
        var dirty = false
        for (record in GitText.decode(stdout).split('\u0000')) {
            if (record.isEmpty()) continue
            if (!record.startsWith("# ")) {
                dirty = true
                break
            }
            val header = record.removePrefix("# ")
            val key = header.substringBefore(' ')
            val value = header.substringAfter(' ', "")
            when (key) {
                "branch.oid" -> oid = value
                "branch.head" -> head = value
                "branch.upstream" -> upstream = value
                "branch.ab" -> ab = parseAheadBehind(value)
            }
        }
        val name = head ?: throw GitParseException("status has no branch.head")
        val commit = oid?.takeIf { it != "(initial)" }?.let(::objectId)
        val headValue =
            if (name == "(detached)") {
                Head.Detached(commit ?: throw GitParseException("detached HEAD without a commit"))
            } else {
                Head.Branch(name, commit)
            }
        val upstreamValue =
            upstream?.let { u ->
                // An upstream with no branch.ab line is gone: the ref it names was deleted.
                ab?.let { (ahead, behind) -> Upstream(u, ahead, behind) } ?: Upstream(u, 0, 0, gone = true)
            }
        return RepositorySummary(headValue, if (headValue is Head.Detached) null else upstreamValue, dirty)
    }

    private fun parseAheadBehind(value: String): Pair<Int, Int> {
        val parts = value.split(' ')
        val ahead = parts.getOrNull(0)?.removePrefix("+")?.toIntOrNull()
        val behind = parts.getOrNull(1)?.removePrefix("-")?.toIntOrNull()
        if (ahead == null || behind == null) throw GitParseException("branch.ab \"$value\"")
        return ahead to behind
    }

    private fun objectId(hex: String): ObjectId = try {
        ObjectId(hex)
    } catch (e: IllegalArgumentException) {
        throw GitParseException(e.message ?: "bad object id")
    }
}

/** `git init` on [folder], with its first branch named [branch]. */
internal class InitCommand(folder: Path, branch: String) : GitCommand<Unit>() {
    override val function = "INIT"
    override val mutates = true
    override val args = listOf("init", "--quiet", "--initial-branch=$branch", folder.toAbsolutePath().toString())

    override fun parse(stdout: ByteArray) = Unit
}

/** `git clone --progress`, read from stderr where git writes its progress. */
internal class CloneCommand(request: CloneRequest) : StreamingGitCommand<CloneProgress.Phase>() {
    override val function = "CLON"
    override val output = GitOutput.STDERR
    override val args =
        buildList {
            add("clone")
            add("--progress")
            request.depth?.let { add("--depth=$it") }
            if (request.singleBranch) add("--single-branch")
            request.branch?.let { add("--branch=$it") }
            if (request.recurseSubmodules) add("--recurse-submodules")
            if (request.partial) add("--filter=blob:none")
            add("--")
            add(request.url)
            add(request.directory.toAbsolutePath().toString())
        }

    override fun parse(stdout: InputStream): Sequence<CloneProgress.Phase> = ProgressParser.parse(stdout)
}

/**
 * Git's progress lines (`Receiving objects:  45% (692117/1507857), 364.78 MiB | 4.60 MiB/s`), which it separates
 * with carriage returns while a phase runs. `LC_ALL=C` keeps the phase names in English.
 */
internal object ProgressParser {
    private val counted = Regex("""^(?:remote: )?([A-Za-z][A-Za-z ]*?):\s+(\d{1,3})% \((\d+)/(\d+)\)""")
    private val uncounted = Regex("""^(?:remote: )?([A-Za-z][A-Za-z ]*?):\s+(\d+)(?:,|$)""")

    fun parse(stream: InputStream): Sequence<CloneProgress.Phase> = sequence {
        val line = StringBuilder()
        val reader = stream.reader(Charsets.UTF_8)
        while (true) {
            val c = reader.read()
            if (c < 0 || c == '\r'.code || c == '\n'.code) {
                parseLine(line.toString())?.let { yield(it) }
                line.clear()
                if (c < 0) break
            } else {
                line.append(c.toChar())
            }
        }
    }

    fun parseLine(text: String): CloneProgress.Phase? {
        counted.find(text)?.let { m ->
            val (name, percent, done, total) = m.destructured
            return CloneProgress.Phase(name, percent.toInt(), done.toLong(), total.toLong())
        }
        uncounted.find(text)?.let { m ->
            val (name, done) = m.destructured
            return CloneProgress.Phase(name, null, done.toLong(), null)
        }
        return null
    }
}

/** Adds [folder] to the global `safe.directory` list. */
internal class TrustCommand(folder: String) : GitCommand<Unit>() {
    override val function = "TRST"
    override val mutates = true
    override val args = listOf("config", "--global", "--add", "safe.directory", folder)

    override fun parse(stdout: ByteArray) = Unit
}
