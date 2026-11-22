// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.models

/** Someone credited with a `Co-authored-by:` trailer (E6: typed `Name <email>` lines, suggestions from the repository). */
public data class CoAuthor(val name: String, val email: String) {
    /** The trailer's value, `Name <email>`. */
    override fun toString(): String = "$name <$email>"

    public companion object {
        private val LINE = Regex("""^\s*([^<>\r\n]+?)\s*<([^<>\s]+@[^<>\s]+)>\s*$""")

        /** `Name <email>`, or null when [line] isn't that. A line break or a stray angle bracket never gets through. */
        public fun parse(line: String): CoAuthor? =
            LINE.matchEntire(line)?.let { CoAuthor(it.groupValues[1], it.groupValues[2]) }
    }
}

/** What the user wrote in the commit box and ticked beside it (Working Copy and Commit, "Commit"). */
public data class CommitDraft(
    val summary: String,
    val description: String = "",
    /** `git commit --signoff`: a `Signed-off-by` trailer for the committer. */
    val signOff: Boolean = false,
    val coAuthors: List<CoAuthor> = emptyList(),
    /** `--no-verify`: skip the pre-commit and commit-msg hooks. Only ever true because the user chose it. */
    val noVerify: Boolean = false,
    /** `--allow-empty`: a commit with no changes, for the user who really wants one. */
    val allowEmpty: Boolean = false,
) {
    /** The message git is given: the summary, a blank line, the description. Trailers are added by git itself. */
    public val message: String
        get() = if (description.isBlank()) summary.trim() else summary.trim() + "\n\n" + description.trim()
}

/** A message split back into its summary and description, for amending and for the template. */
public data class CommitMessageText(val summary: String, val description: String) {
    public companion object {
        /** The first line is the summary; what follows the blank line after it is the description. */
        public fun of(message: String): CommitMessageText {
            val text = message.trim('\n', '\r', ' ', '\t')
            val summary = text.lineSequence().firstOrNull().orEmpty().trim()
            val rest = text.removePrefix(text.lineSequence().firstOrNull().orEmpty()).trim('\n', '\r')
            return CommitMessageText(summary, rest.trim())
        }
    }
}

/**
 * The 50/72 guide as data (the UI draws it): where the summary or the description is longer than the usual limits.
 * Only a hint: nothing here stops a commit.
 */
public data class CommitGuide(
    val summaryLength: Int,
    val summaryTooLong: Boolean,
    /** 1-based line numbers of the description's lines over 72 characters. */
    val longDescriptionLines: List<Int>,
    /** The description starts right under the summary instead of after a blank line (only seen in raw messages). */
    val missingBlankLine: Boolean,
) {
    public companion object {
        public const val SUMMARY_LIMIT: Int = 50
        public const val LINE_LIMIT: Int = 72

        public fun of(summary: String, description: String): CommitGuide {
            val long =
                description.lines().withIndex().filter { (_, line) -> line.length > LINE_LIMIT }.map { it.index + 1 }
            return CommitGuide(
                summaryLength = summary.trim().length,
                summaryTooLong = summary.trim().length > SUMMARY_LIMIT,
                longDescriptionLines = long,
                missingBlankLine = false,
            )
        }

        /** The guide for a whole message as git stores it. */
        public fun ofMessage(message: String): CommitGuide {
            val lines = message.trim().lines()
            val summary = lines.firstOrNull().orEmpty()
            val missing = lines.size > 1 && lines[1].isNotBlank()
            val body = lines.drop(if (missing) 1 else 2).joinToString("\n")
            return of(summary, body).copy(missingBlankLine = missing)
        }
    }
}
