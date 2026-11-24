// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.source.local

import com.srctool.graft.core.git.GitCommand
import com.srctool.graft.core.git.GitParseException
import com.srctool.graft.data.git.api.models.CleanScope
import com.srctool.graft.data.git.api.models.IgnoreExplanation
import java.nio.file.Path
import java.nio.file.Paths

/** Paths are literal, never patterns: a file called `*.txt` is one file. */
private val LITERAL = mapOf("GIT_LITERAL_PATHSPECS" to "1")

private fun nulSplit(stdout: ByteArray): List<String> =
    GitText.decode(stdout).split('\u0000').filter { it.isNotEmpty() }

/** Where git keeps [path] of this repository (`info/exclude` is in the common folder, even in a linked worktree). */
internal class GitPathCommand(path: String) : GitCommand<Path>() {
    override val function = "IGNR"
    override val args = listOf("rev-parse", "--path-format=absolute", "--git-path", path)

    override fun parse(stdout: ByteArray): Path =
        Paths.get(GitText.decode(stdout).trimEnd('\n').ifEmpty { throw GitParseException("no path") })
}

/** `core.excludesFile` with `~` expanded, or null when it isn't set (exit 1). */
internal class GlobalExcludesCommand : GitCommand<String?>() {
    override val function = "IGNR"
    override val args = listOf("config", "--global", "--path", "--get", "core.excludesFile")
    override val successExitCodes = setOf(0, 1)

    override fun parse(stdout: ByteArray): String? = GitText.decode(stdout).trimEnd('\n').ifBlank { null }
}

/**
 * The files [excludeFile]'s patterns alone would ignore: untracked ones, or with [cached] the tracked ones. Only that
 * file's patterns count (no `.gitignore`), which is what "what would this pattern match" asks. Its patterns are anchored
 * at the repository root, wherever git runs.
 */
internal class IgnoredByFileCommand(excludeFile: Path, cached: Boolean) : GitCommand<List<String>>() {
    override val function = "IGNR"
    override val args =
        listOf("ls-files", "-z", if (cached) "-c" else "-o", "-i", "--exclude-from=$excludeFile")
    override val environment = LITERAL

    override fun parse(stdout: ByteArray): List<String> = nulSplit(stdout)
}

/** Every untracked file the repository's own ignore rules hide. */
internal class IgnoredFilesCommand : GitCommand<Set<String>>() {
    override val function = "IGNR"
    override val args = listOf("ls-files", "-z", "-o", "-i", "--exclude-standard")

    override fun parse(stdout: ByteArray): Set<String> = nulSplit(stdout).toSet()
}

/**
 * `check-ignore -v -z --non-matching` over stdin (paths NUL-separated). `--no-index` explains tracked paths as well.
 * Exit 1 means nothing matched, which is an answer.
 */
internal class CheckIgnoreCommand : GitCommand<List<IgnoreExplanation>>() {
    override val function = "IGNR"
    override val args = listOf("check-ignore", "-v", "-z", "--non-matching", "--no-index", "--stdin")
    override val successExitCodes = setOf(0, 1)

    override fun parse(stdout: ByteArray): List<IgnoreExplanation> {
        val fields = GitText.decode(stdout).split('\u0000')
        // Each record is source, line, pattern, path; the last split is the empty tail after the final NUL.
        val records = fields.dropLast(1).chunked(4)
        return records.mapNotNull { record ->
            if (record.size != 4) throw GitParseException("check-ignore record with ${record.size} fields")
            val (source, line, pattern, path) = record
            if (source.isEmpty()) {
                null
            } else {
                IgnoreExplanation(path, source, line.toIntOrNull() ?: throw GitParseException("line $line"), pattern)
            }
        }
    }
}

/** `git rm --cached -r`: untracks the paths and leaves the files. Paths come on stdin. */
internal class RemoveCachedCommand : GitCommand<Unit>() {
    override val function = "IGNR"
    override val mutates = true
    override val args = listOf("rm", "-r", "--cached", "-q", "--pathspec-from-file=-", "--pathspec-file-nul")
    override val environment = LITERAL

    override fun parse(stdout: ByteArray) = Unit
}

private fun CleanScope.flags(): List<String> = when (this) {
    CleanScope.UNTRACKED -> emptyList()
    CleanScope.UNTRACKED_AND_IGNORED -> listOf("-x")
    CleanScope.IGNORED_ONLY -> listOf("-X")
}

/**
 * `git clean -n -d`: what would be removed. A nested repository is only listed with [nested] (`-ff`), so the difference
 * between the two lists is the nested repositories; the plain list's "would skip repository" lines are left out, since
 * the `-ff` list names them with the folder git would remove. Names are C-quoted by git when they hold odd characters.
 */
internal class CleanListCommand(scope: CleanScope, nested: Boolean) : GitCommand<List<String>>() {
    override val function = "CLEN"
    override val args =
        buildList {
            addAll(listOf("clean", "-n", "-d"))
            addAll(scope.flags())
            if (nested) add("-ff")
        }

    override fun parse(stdout: ByteArray): List<String> = GitText.decode(stdout).split('\n').mapNotNull { line ->
        val name =
            when {
                line.startsWith("Would remove ") -> line.removePrefix("Would remove ")
                else -> return@mapNotNull null
            }
        if (name.length >= 2 && name.startsWith("\"") &&
            name.endsWith("\"")
        ) {
            GuardDiffScanner.unquote(name)
        } else {
            name
        }
    }
}

/** `git clean -f -d` on exactly [paths]. [nested] adds the second `-f` that lets it remove a nested repository. */
internal class CleanRunCommand(scope: CleanScope, nested: Boolean, paths: List<String>) : GitCommand<Unit>() {
    override val function = "CLEN"
    override val mutates = true
    override val args =
        buildList {
            addAll(listOf("clean", "-q", "-d", "-f"))
            if (nested) add("-f")
            addAll(scope.flags())
            add("--")
            addAll(paths)
        }
    override val environment = LITERAL

    override fun parse(stdout: ByteArray) = Unit
}
