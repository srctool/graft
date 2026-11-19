// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.search

import com.srctool.graft.presentation.search.SearchCandidate

/** A candidate that matched, with how well, and which characters of its title to mark. */
internal data class Ranked(val candidate: SearchCandidate, val score: Int, val marks: List<Int>)

/** How well [text] matches a query: [score] (bigger is better) and the [marks] (indexes into the text). */
internal data class Match(val score: Int, val marks: List<Int>)

/**
 * Ranking for Search Everywhere (wireframe S39). A prefix of the name beats the start of a word (after a separator or
 * at a capital), which beats a part of the name, which beats the letters in order; ties go to the shorter title, then
 * the newer, then the title itself. A query of several words needs every word to match. A candidate's [extra][
 * SearchCandidate.extra] (a SHA, a path) matches too, at half the score and with nothing marked.
 */
internal object SearchRanking {
    private const val PREFIX = 1000
    private const val WORD = 800
    private const val PART = 600
    private const val LETTERS = 300

    fun rank(query: String, candidates: List<SearchCandidate>): List<Ranked> {
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return candidates.map { Ranked(it, 0, emptyList()) }
        return candidates.mapNotNull { rankOne(words, it) }.sortedWith(order)
    }

    private val order: Comparator<Ranked> =
        compareByDescending<Ranked> { it.score }
            .thenBy { it.candidate.title.length }
            .thenByDescending { it.candidate.recency }
            .thenBy { it.candidate.title }

    private fun rankOne(words: List<String>, candidate: SearchCandidate): Ranked? {
        var score = Int.MAX_VALUE
        val marks = sortedSetOf<Int>()
        for (word in words) {
            val inTitle = match(word, candidate.title)
            val inExtra = candidate.extra?.let { match(word, it) }?.let { Match(it.score / 2, emptyList()) }
            val best = listOfNotNull(inTitle, inExtra).maxByOrNull { it.score } ?: return null
            score = minOf(score, best.score)
            marks += best.marks
        }
        return Ranked(candidate, score, marks.toList())
    }

    /** [word] is already lower case. */
    fun match(word: String, text: String): Match? {
        if (word.isEmpty()) return null
        val lower = text.lowercase()
        if (lower.startsWith(word)) return Match(PREFIX, word.indices.toList())
        wordStart(word, text, lower)?.let { return Match(WORD, (it until it + word.length).toList()) }
        val at = lower.indexOf(word)
        if (at >= 0) return Match(PART, (at until at + word.length).toList())
        return letters(word, lower)
    }

    /** The first place [word] starts a word of [text]: after anything that isn't a letter or digit, or at a capital. */
    private fun wordStart(word: String, text: String, lower: String): Int? {
        var from = 1
        while (true) {
            val i = lower.indexOf(word, from)
            if (i < 0) return null
            val before = text[i - 1]
            if (!before.isLetterOrDigit() || (text[i].isUpperCase() && before.isLowerCase())) return i
            from = i + 1
        }
    }

    private fun letters(word: String, lower: String): Match? {
        val marks = ArrayList<Int>(word.length)
        var from = 0
        for (c in word) {
            val i = lower.indexOf(c, from)
            if (i < 0) return null
            marks += i
            from = i + 1
        }
        val gaps = marks.last() - marks.first() - (word.length - 1)
        return Match((LETTERS - minOf(gaps, LETTERS - 1)).coerceAtLeast(1), marks)
    }
}
