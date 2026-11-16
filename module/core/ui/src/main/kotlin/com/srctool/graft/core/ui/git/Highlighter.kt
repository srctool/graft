// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.git

import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.PhraseLocation
import dev.snipme.highlights.model.SyntaxLanguage

/** What a span of code is, for its color (One's syntax colors, `GraftSyntax`). */
public enum class SyntaxKind { KEYWORD, STRING, NUMBER, COMMENT, ANNOTATION, OPERATOR }

/** A highlighted span: [start] inclusive, [end] exclusive, in the text that was highlighted. */
public data class SyntaxSpan(val start: Int, val end: Int, val kind: SyntaxKind)

/**
 * Syntax highlighting for diffs (G1 plan, D7). Behind an interface so TextMate grammars or tree-sitter can replace
 * the default ([Default], `dev.snipme:highlights`) without touching [DiffViewer]. Called off the main thread.
 */
public fun interface Highlighter {
    /** The spans of [code], a file at [path]; empty when the language isn't known. */
    public fun highlight(code: String, path: String): List<SyntaxSpan>

    public companion object {
        /** `dev.snipme:highlights`: pure Kotlin, a handful of languages. */
        public val Default: Highlighter = SnipmeHighlighter

        /** No highlighting: plain text. */
        public val None: Highlighter = Highlighter { _, _ -> emptyList() }
    }
}

private object SnipmeHighlighter : Highlighter {
    private val languages =
        mapOf(
            "c" to SyntaxLanguage.C, "h" to SyntaxLanguage.C,
            "cc" to SyntaxLanguage.CPP, "cpp" to SyntaxLanguage.CPP, "cxx" to SyntaxLanguage.CPP,
            "hpp" to SyntaxLanguage.CPP,
            "dart" to SyntaxLanguage.DART,
            "java" to SyntaxLanguage.JAVA,
            "kt" to SyntaxLanguage.KOTLIN, "kts" to SyntaxLanguage.KOTLIN,
            "rs" to SyntaxLanguage.RUST,
            "cs" to SyntaxLanguage.CSHARP,
            "coffee" to SyntaxLanguage.COFFEESCRIPT,
            "js" to SyntaxLanguage.JAVASCRIPT, "mjs" to SyntaxLanguage.JAVASCRIPT, "cjs" to SyntaxLanguage.JAVASCRIPT,
            "jsx" to SyntaxLanguage.JAVASCRIPT,
            "pl" to SyntaxLanguage.PERL, "pm" to SyntaxLanguage.PERL,
            "py" to SyntaxLanguage.PYTHON,
            "rb" to SyntaxLanguage.RUBY,
            "sh" to SyntaxLanguage.SHELL, "bash" to SyntaxLanguage.SHELL, "zsh" to SyntaxLanguage.SHELL,
            "swift" to SyntaxLanguage.SWIFT,
            "ts" to SyntaxLanguage.TYPESCRIPT, "tsx" to SyntaxLanguage.TYPESCRIPT,
            "go" to SyntaxLanguage.GO,
            "php" to SyntaxLanguage.PHP,
        )

    override fun highlight(code: String, path: String): List<SyntaxSpan> {
        val extension = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        val language = languages[extension] ?: return emptyList()
        val structure = Highlights.Builder().code(code).language(language).build().getCodeStructure()
        val spans = ArrayList<SyntaxSpan>()
        fun add(locations: Set<PhraseLocation>, kind: SyntaxKind) {
            locations.forEach { if (it.end > it.start) spans += SyntaxSpan(it.start, minOf(it.end, code.length), kind) }
        }
        add(structure.keywords, SyntaxKind.KEYWORD)
        add(structure.literals, SyntaxKind.NUMBER)
        add(structure.annotations, SyntaxKind.ANNOTATION)
        add(structure.marks, SyntaxKind.OPERATOR)
        // Strings and comments last, so they win over keywords found inside them.
        add(structure.strings, SyntaxKind.STRING)
        add(structure.comments, SyntaxKind.COMMENT)
        add(structure.multilineComments, SyntaxKind.COMMENT)
        return spans
    }
}

/**
 * [spans] of the text made of [lines] joined by `\n`, split back into per-line spans with line-relative offsets, so a
 * hunk's side can be highlighted as one text (a comment that starts above still colors the lines below).
 */
internal fun splitSpans(lines: List<String>, spans: List<SyntaxSpan>): List<List<SyntaxSpan>> {
    val starts = IntArray(lines.size)
    var offset = 0
    lines.forEachIndexed { i, line ->
        starts[i] = offset
        offset += line.length + 1
    }
    val result = List(lines.size) { ArrayList<SyntaxSpan>() }
    for (span in spans) {
        for (i in lines.indices) {
            val lineStart = starts[i]
            val lineEnd = lineStart + lines[i].length
            if (span.end <= lineStart || span.start >= lineEnd) continue
            result[i] +=
                SyntaxSpan(maxOf(span.start, lineStart) - lineStart, minOf(span.end, lineEnd) - lineStart, span.kind)
        }
    }
    return result
}
