// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.search

import com.srctool.graft.presentation.search.SearchCandidate
import com.srctool.graft.presentation.search.SearchKind
import com.srctool.graft.presentation.search.SearchTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal fun candidate(
    title: String,
    kind: SearchKind = SearchKind.Branch,
    extra: String? = null,
    recency: Long = 0,
    key: String = title,
) = SearchCandidate(key, kind, title, SearchTarget.RunAction(key), extra = extra, recency = recency)

class SearchRankingTest {
    private fun titles(query: String, vararg names: String) =
        SearchRanking.rank(query, names.map { candidate(it) }).map { it.candidate.title }

    @Test
    fun `a prefix beats the start of a word, a part, and letters in order`() {
        assertEquals(
            listOf("login-page", "feature/login", "relogin", "l-o-g-i-n"),
            titles("login", "relogin", "feature/login", "l-o-g-i-n", "login-page"),
        )
    }

    @Test
    fun `a capital starts a word too, and letters in order match across the name`() {
        assertEquals(1, titles("login", "myLoginScreen").size)
        assertEquals(listOf("feature/login"), titles("fl", "feature/login", "main"))
        assertEquals(emptyList(), titles("zz", "feature/login"))
    }

    @Test
    fun `ties go to the shorter title, then the newer, then the title`() {
        val ranked =
            SearchRanking.rank(
                "ab",
                listOf(
                    candidate("abz", recency = 1),
                    candidate("aby", recency = 9),
                    candidate("ab"),
                    candidate("abx", recency = 9),
                ),
            )
        assertEquals(listOf("ab", "abx", "aby", "abz"), ranked.map { it.candidate.title })
    }

    @Test
    fun `case is ignored and the matched characters are marked`() {
        val ranked = SearchRanking.rank("LOG", listOf(candidate("feature/login"))).single()
        assertEquals(listOf(8, 9, 10), ranked.marks)
    }

    @Test
    fun `every word of the query must match, and the weakest decides the score`() {
        assertEquals(listOf("fix login redirect"), titles("fix redirect", "fix login redirect", "add login"))
        assertEquals(emptyList(), titles("fix zzz", "fix login redirect"))
    }

    @Test
    fun `a SHA or path in extra matches at half the score and marks nothing`() {
        val ranked = SearchRanking.rank("a1b2", listOf(candidate("Add login form", extra = "a1b2c3d4e5")))
        val one = ranked.single()
        assertEquals(500, one.score)
        assertEquals(emptyList(), one.marks)
        // A title match of the same word outranks it.
        val both =
            SearchRanking.rank(
                "a1b2",
                listOf(candidate("x", extra = "a1b2c3"), candidate("a1b2 tag")),
            )
        assertEquals("a1b2 tag", both.first().candidate.title)
    }

    @Test
    fun `no query ranks nothing and keeps the order`() {
        val all = listOf(candidate("b"), candidate("a"))
        assertEquals(listOf("b", "a"), SearchRanking.rank("  ", all).map { it.candidate.title })
        assertNull(SearchRanking.match("", "x"))
    }
}
