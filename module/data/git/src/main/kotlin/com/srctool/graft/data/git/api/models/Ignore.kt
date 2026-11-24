// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.models

import java.nio.file.Path

/** Where an ignore pattern goes (Ignore and Clean, "Ignore from the changes panel"). */
public sealed interface IgnoreTarget {
    /** The `.gitignore` in [directory] (relative to the repository root; empty for the root one), committed and shared. */
    public data class Gitignore(val directory: String = "") : IgnoreTarget

    /** `.git/info/exclude`: this clone only. */
    public data object InfoExclude : IgnoreTarget

    /**
     * The global excludes file: `core.excludesFile` when set, else git's own default (`$XDG_CONFIG_HOME/git/ignore`),
     * which git reads without any config entry. Created when missing. A pattern here applies to every repository.
     */
    public data object GlobalExcludes : IgnoreTarget
}

/** The patterns the "Ignore" menu offers for a path. [base] is the folder of the `.gitignore` they will go into. */
public object IgnorePatterns {
    /** `/path/to/file.log`: this file only. */
    public fun file(path: String, base: String = ""): String = "/" + escape(relativeTo(path, base))

    /** `*.log`: every file with this extension; null when the name has none. */
    public fun extension(path: String): String? {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return null
        return "*." + escape(name.substring(dot + 1))
    }

    /** `/build/`: this folder and everything in it. The repository's own folder has no pattern, so [path] can't be empty. */
    public fun folder(path: String, base: String = ""): String {
        val relative = relativeTo(path.trimEnd('/'), base)
        require(relative.isNotEmpty()) { "a folder pattern needs a folder" }
        return "/" + escape(relative) + "/"
    }

    private fun relativeTo(path: String, base: String): String {
        val folder = base.trim('/')
        if (folder.isEmpty()) return path.trimStart('/')
        require(path.startsWith("$folder/")) { "$path is not under $folder" }
        return path.removePrefix("$folder/")
    }

    /** Backslashes before what git reads as pattern syntax, and a trailing space, which git would drop otherwise. */
    internal fun escape(text: String): String {
        val out = StringBuilder()
        for ((i, c) in text.withIndex()) {
            when {
                c in SPECIAL -> out.append('\\').append(c)
                c == ' ' && i == text.lastIndex -> out.append("\\ ")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private const val SPECIAL = "\\*?[]"
}

/** A warning about patterns that were typed or picked, shown with the preview. */
public sealed interface IgnoreWarning {
    /** [pattern] re-includes a path whose folder [folder] is ignored: git never looks inside an ignored folder. */
    public data class ParentIgnored(val pattern: String, val folder: String) : IgnoreWarning
}

/**
 * What patterns would match, before they are written. [untracked] are files ignoring would hide; [tracked] are files
 * git already tracks, which ignoring does not affect (`git rm --cached` does). Each list is cut at [LIMIT] paths and
 * [truncated] says so.
 */
public data class IgnorePreview(
    val untracked: List<String>,
    val tracked: List<String>,
    val warnings: List<IgnoreWarning>,
    val truncated: Boolean,
) {
    public companion object {
        public const val LIMIT: Int = 5_000
    }
}

/** What adding patterns to an ignore file did: [skipped] were already there. */
public data class IgnoreWrite(
    val file: Path,
    val added: List<String>,
    val skipped: List<String>,
    /** The file did not exist before. */
    val created: Boolean,
)

/** `git check-ignore -v`: the rule that ignores [path], from [source] (as git prints it) at [line]. */
public data class IgnoreExplanation(val path: String, val source: String, val line: Int, val pattern: String) {
    /** A `!` rule: the path is explicitly not ignored. */
    val negated: Boolean get() = pattern.startsWith("!")
}

/** One template of the bundled `github/gitignore` collection. */
public data class IgnoreTemplate(val name: String, val lines: List<String>)
