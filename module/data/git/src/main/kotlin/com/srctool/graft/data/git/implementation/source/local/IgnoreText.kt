// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.source.local

import com.srctool.graft.data.git.api.models.IgnoreTemplate
import com.srctool.graft.data.git.api.models.IgnoreWarning
import com.srctool.graft.data.git.api.models.PathGlob
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Reading, merging and writing ignore files: pure text work, no git. */
internal object IgnoreText {
    /** What [merge] decided: [text] is the bytes to append (empty when nothing is new). */
    internal class Merged(val added: List<String>, val skipped: List<String>, val text: String)

    /**
     * Lines of [incoming] that [existing] doesn't have yet. Comment lines count as lines (a template's `# Logs` isn't
     * repeated), runs of blank lines collapse, and nothing is added when no pattern is: a comment alone is not a change.
     */
    fun merge(existing: List<String>, incoming: List<String>, eol: String = "\n"): Merged {
        val have = existing.map { it.trimEnd('\r') }.toMutableSet()
        val endsBlank = existing.lastOrNull()?.isBlank() ?: true
        val added = ArrayList<String>()
        val skipped = ArrayList<String>()
        for (raw in incoming) {
            val line = raw.trimEnd('\r', '\n')
            when {
                line.isBlank() -> if (added.isNotEmpty() && added.last().isNotBlank()) added += ""

                line in have -> skipped += line

                else -> {
                    added += line
                    have += line
                }
            }
        }
        while (added.isNotEmpty() && added.last().isBlank()) added.removeLast()
        if (added.none { !it.startsWith("#") && it.isNotBlank() }) {
            return Merged(emptyList(), skipped + added.filter { it.isNotBlank() }, "")
        }
        // A blank line between what was there and a new comment block keeps templates readable.
        val lead = if (!endsBlank && added.first().startsWith("#")) eol else ""
        return Merged(added.filter { it.isNotBlank() }, skipped, lead + added.joinToString(eol) + eol)
    }

    /**
     * Writes [file] as [bytes] through a sibling and a rename, so a crash leaves the old or the new file, never half.
     * A symbolic link is written through, not replaced. Parent folders are created.
     */
    fun writeAtomic(file: Path, bytes: ByteArray) {
        val target = if (Files.isSymbolicLink(file)) file.toRealPath() else file
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, ".graft-", ".tmp")
        try {
            Files.write(temp, bytes)
            if (Files.exists(target)) {
                try {
                    Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target))
                } catch (_: UnsupportedOperationException) {
                    // Not a POSIX file system: nothing to copy.
                }
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            try {
                Files.deleteIfExists(temp)
            } catch (_: IOException) {
                // The rename already moved it, or the folder is going away: nothing to clean.
            }
        }
    }

    /**
     * A pattern for the `.gitignore` in [base], as `--exclude-from` needs it: that option anchors patterns at the
     * repository root, so a pattern of a nested file is rewritten to say where the file is. Null for blank and comment
     * lines.
     */
    fun forBase(line: String, base: String): String? {
        if (line.isBlank() || line.startsWith("#")) return null
        val folder = base.trim('/')
        if (folder.isEmpty()) return line
        val negated = line.startsWith("!")
        val body = if (negated) line.substring(1) else line
        val anchored = body.startsWith("/") || body.trimEnd('/').contains('/')
        val moved = if (anchored) "/$folder/" + body.removePrefix("/") else "/$folder/**/$body"
        return if (negated) "!$moved" else moved
    }

    /** True when the (already rebased) ignore line [pattern] ignores the folder [folder] by name. */
    fun ignoresFolder(pattern: String, folder: String): Boolean {
        if (pattern.startsWith("!") || pattern.startsWith("#") || pattern.isBlank()) return false
        val core = pattern.trimEnd('/')
        val anchored = pattern.startsWith("/") || core.contains('/')
        val glob = if (anchored) core.removePrefix("/") else "**/$core"
        return PathGlob.matches(glob, folder)
    }

    /**
     * The folders a negation `!a/b/c` needs open: `a` and `a/b`, shortest first; empty for a negation of a bare name or
     * a pattern with wildcards, whose folders aren't known without the files.
     */
    fun parentsOfNegation(rebased: String): List<String> {
        if (!rebased.startsWith("!")) return emptyList()
        val path = rebased.substring(1).removePrefix("/").trimEnd('/')
        if (path.any { it in "*?[\\" } || !path.contains('/')) return emptyList()
        val parts = path.split('/')
        return (1 until parts.size).map { parts.take(it).joinToString("/") }
    }

    /** Warnings for negations whose parent folder an earlier line of [rebased] ignores. */
    fun parentWarnings(original: List<String>, rebased: List<String?>): List<IgnoreWarning> {
        val warnings = ArrayList<IgnoreWarning>()
        for ((i, line) in rebased.withIndex()) {
            line ?: continue
            for (parent in parentsOfNegation(line)) {
                val earlier = rebased.take(i).filterNotNull()
                if (earlier.any { ignoresFolder(it, parent) }) {
                    warnings += IgnoreWarning.ParentIgnored(original[i], parent)
                    break
                }
            }
        }
        return warnings
    }
}

/** The `github/gitignore` templates bundled as resources (`gitignore-templates/`, CC0). */
internal object BundledTemplates {
    private const val ROOT = "/gitignore-templates/"

    val all: List<IgnoreTemplate> by lazy {
        read("index.txt").lines().map { it.trim() }.filter { it.isNotEmpty() }.map { name ->
            IgnoreTemplate(
                name,
                read("$name.gitignore").lines().map {
                    it.trimEnd('\r')
                }.dropLastWhile { it.isEmpty() },
            )
        }
    }

    val license: String by lazy { read("LICENSE") }

    private fun read(name: String): String =
        (BundledTemplates::class.java.getResourceAsStream(ROOT + name) ?: error("missing resource $name"))
            .use { it.readBytes().toString(Charsets.UTF_8) }
}
