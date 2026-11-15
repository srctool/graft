// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.GitParseException
import com.srctool.graft.data.git.api.models.AuthorType
import com.srctool.graft.data.git.api.models.People
import com.srctool.graft.data.git.implementation.source.local.LogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LogParserTest {
    private val people = People()
    private val rows = LogParser.parse(Fixtures.read("log", "graph").inputStream(), people).toList()
    private fun subject(s: String) = rows.single { it.subject == s }

    @Test
    fun `reads every commit of the graph repository in topological order`() {
        // init, feature work, main 2, merge, docs root, merge docs, work a/b/c, octopus, empty, crafted.
        assertEquals(12, rows.size)
        val index = rows.withIndex().associate { it.value.id to it.index }
        rows.forEachIndexed { i, row ->
            row.parents.forEach { p -> assertTrue(index.getValue(p) > i, "parent after child") }
        }
    }

    @Test
    fun `roots, merges and the octopus`() {
        assertEquals(setOf("init", "docs root"), rows.filter { it.isRoot }.map { it.subject }.toSet())
        assertEquals(2, subject("Merge feature").parents.size)
        assertEquals(4, subject("Octopus").parents.size)
        assertEquals(subject("main 2").id, subject("Merge feature").parents.first())
    }

    @Test
    fun `author types tell people, AI co-authored commits and bots apart`() {
        val authored = LogParser.parse(Fixtures.read("log", "authors").inputStream(), People()).toList()
        assertEquals(AuthorsRepo.expected, authored.reversed().associate { it.subject to it.authorType })
    }

    @Test
    fun `the graph repository's commits are all by people`() {
        assertTrue(rows.all { it.authorType == AuthorType.PERSON })
    }

    @Test
    fun `an empty subject is kept`() {
        assertEquals(1, rows.count { it.subject.isEmpty() })
    }

    @Test
    fun `Latin-1 names and messages decode as Windows-1252`() {
        val crafted = subject(GraphRepo.CRAFTED_SUBJECT)
        assertEquals(GraphRepo.CRAFTED_AUTHOR, people[crafted.authorIndex].name)
        assertEquals("jose@graft.invalid", people[crafted.authorIndex].email)
    }

    @Test
    fun `people are interned once`() {
        // The sandbox identity, the crafted author, and the committer-only entry (no email in the format).
        assertEquals(3, people.size)
        assertTrue(rows.all { it.authorTime == 1767225600L && it.committerTime == 1767225600L })
    }

    @Test
    fun `the stash and Graft's refs stay out of the log`() {
        assertTrue(
            rows.none {
                it.subject.startsWith("WIP on") || it.subject.startsWith("On main: wip") ||
                    it.subject.startsWith("index on")
            },
        )
    }

    @Test
    fun `a truncated record is a parse error`() {
        val cut = Fixtures.read("log", "graph").let { it.copyOf(it.size - 10) }
        assertFailsWith<GitParseException> { LogParser.parse(cut.inputStream(), People()).toList() }
    }

    @Test
    fun `garbage where a hash belongs is a parse error`() {
        val bad = "not-a-hash\u0000\u0000a\u0000b\u00001\u0000c\u00001\u0000s\u0000\u0000".toByteArray()
        assertFailsWith<GitParseException> { LogParser.parse(bad.inputStream(), People()).toList() }
    }

    @Test
    fun `no output is no rows`() {
        assertEquals(0, LogParser.parse(ByteArray(0).inputStream(), People()).count())
    }
}
