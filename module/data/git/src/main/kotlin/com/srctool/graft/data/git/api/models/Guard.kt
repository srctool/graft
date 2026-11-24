// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.models

/** The five checks of Commit Guards. */
public enum class GuardRule {
    /** A known token format, a private key header, or a high-entropy value next to `password`, `secret` or `token`. */
    SECRET,

    /** A file that shouldn't be in Git by name: `.env`, a private key, a keystore, `credentials.json`. */
    SENSITIVE_FILE,

    /** A file over the size limits. */
    LARGE_FILE,

    /** `<<<<<<<` or `>>>>>>>` left in a text file. */
    CONFLICT_MARKER,

    /** `Readme.md` and `README.md`, which break checkouts on macOS and Windows. */
    CASE_COLLISION,
}

public enum class GuardSeverity {
    /** Shown, and the commit or push can go on. */
    WARN,

    /** Stops the commit or push until the user fixes it or allows it. */
    BLOCK,
}

/**
 * One thing a check found. [excerpt] is **masked**: a secret's value never appears here, in a log or in anything
 * built from this (Threat Model T12). [fingerprint] is stable when the line moves, and doesn't reveal the value.
 */
public data class GuardFinding(
    val rule: GuardRule,
    val severity: GuardSeverity,
    /** The path in the repository, with `/` separators. */
    val path: String,
    /** The line in the new file; null for a finding about the whole file. */
    val line: Int?,
    /** What matched, masked (`ghp_****…a91F`), or the file's size, or the other path of a case collision. */
    val excerpt: String,
    /** Which kind of secret or file, for the dialog (`GitHub token`, `Private key`, `.env file`). */
    val label: String,
    val fingerprint: String,
)

/** The outcome of a scan: what still needs an answer, and what the allow-lists let through. */
public data class GuardReport(val findings: List<GuardFinding>, val allowed: List<GuardFinding> = emptyList()) {
    val blocking: List<GuardFinding> get() = findings.filter { it.severity == GuardSeverity.BLOCK }
    val warnings: List<GuardFinding> get() = findings.filter { it.severity == GuardSeverity.WARN }

    /** True when nothing blocks. */
    val clean: Boolean get() = blocking.isEmpty()
}

/** What to scan. */
public sealed interface GuardTarget {
    /** What is staged, against `HEAD`. */
    public data object Staged : GuardTarget

    /** What a changelist (or any selection) would commit: `HEAD` plus [selection], without touching the index. */
    public data class Selection(val selection: ChangeSelection, val contextLines: Int = 3) : GuardTarget

    /** Everything [to] has that [from] doesn't, as one combined diff: what a push would send. */
    public data class Range(val from: String, val to: String) : GuardTarget {
        init {
            require(!from.startsWith("-") && !to.startsWith("-")) { "a revision can't start with a dash" }
        }
    }
}

/** Which checks run and where their limits are (Commit Guards, "Checks"). */
public data class GuardOptions(
    val rules: Set<GuardRule> = GuardRule.entries.toSet(),
    /** Over this a file gets a warning (and a hint about Git LFS). */
    val warnBytes: Long = 10L * 1024 * 1024,
    /** Over this a file is blocked: GitHub's hard limit. */
    val blockBytes: Long = 100L * 1024 * 1024,
)

/**
 * What the user has allowed: findings by fingerprint (`guard-allow.json`, local, and `.graft/guard.toml`, shared) and
 * paths by pattern (`.graft/guard.toml`; `*` stays inside a folder, `**` crosses folders).
 */
public data class Allowances(val fingerprints: Set<String> = emptySet(), val pathPatterns: List<String> = emptyList()) {
    public fun allows(finding: GuardFinding): Boolean =
        finding.fingerprint in fingerprints || pathPatterns.any { PathGlob.matches(it, finding.path) }
}

/** Path patterns for allow-lists: `*` and `?` stay in one folder, `**` crosses folders, a leading `/` is ignored. */
public object PathGlob {
    public fun matches(pattern: String, path: String): Boolean = regex(pattern.removePrefix("/")).matches(path)

    private fun regex(pattern: String): Regex {
        val out = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '*' && pattern.startsWith("**/", i) -> {
                    out.append("(?:.*/)?")
                    i += 3
                    continue
                }

                c == '*' && pattern.startsWith("**", i) -> {
                    out.append(".*")
                    i += 2
                    continue
                }

                c == '*' -> out.append("[^/]*")

                c == '?' -> out.append("[^/]")

                else -> out.append(Regex.escape(c.toString()))
            }
            i++
        }
        return Regex(out.toString())
    }
}
