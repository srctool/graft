// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.GitParseException
import com.srctool.graft.data.git.api.models.People
import com.srctool.graft.data.git.api.models.RefKind
import com.srctool.graft.data.git.implementation.source.local.LogParser
import com.srctool.graft.data.git.implementation.source.local.RefsParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RefsParserTest {
    private val refs = RefsParser.parse(Fixtures.read("refs", "graph")).associateBy { it.name }
    private val rows = LogParser.parse(Fixtures.read("log", "graph").inputStream(), People()).toList()

    @Test
    fun `lists branches, remote branches, tags, the stash and Graft's refs`() {
        assertEquals(RefKind.LOCAL_BRANCH, refs.getValue("refs/heads/main").kind)
        assertEquals(RefKind.REMOTE_BRANCH, refs.getValue("refs/remotes/origin/main").kind)
        assertEquals(RefKind.TAG, refs.getValue("refs/tags/v1").kind)
        assertEquals(RefKind.STASH, refs.getValue("refs/stash").kind)
        assertEquals(RefKind.GRAFT, refs.getValue("refs/graft/shelves/test").kind)
        assertEquals("shelves/test", refs.getValue("refs/graft/shelves/test").shortName)
    }

    @Test
    fun `HEAD's branch and its upstream`() {
        val main = refs.getValue("refs/heads/main")
        assertTrue(main.isHead)
        assertEquals("refs/remotes/origin/main", main.upstream)
        assertEquals(2, main.ahead)
        assertEquals(0, main.behind)
        assertFalse(refs.getValue("refs/heads/feature").isHead)
        assertNull(refs.getValue("refs/heads/feature").upstream)
        assertNull(refs.getValue("refs/heads/feature").ahead)
    }

    @Test
    fun `annotated tags peel to their commit, lightweight tags point at it`() {
        val annotated = refs.getValue("refs/tags/v1")
        val mainTwo = rows.single { it.subject == "main 2" }.id
        assertEquals(mainTwo, annotated.peeled)
        assertTrue(annotated.target != mainTwo)
        assertEquals(mainTwo, annotated.commit)
        val light = refs.getValue("refs/tags/v0-light")
        assertNull(light.peeled)
        assertEquals(rows.single { it.subject == "init" }.id, light.commit)
    }

    @Test
    fun `the remote HEAD is a symbolic ref`() {
        assertEquals("refs/remotes/origin/main", refs.getValue("refs/remotes/origin/HEAD").symbolicTarget)
    }

    @Test
    fun `a gone upstream and a malformed line`() {
        val sha = "a".repeat(40)
        val line = listOf(
            "refs/heads/x",
            sha,
            "commit",
            "",
            " ",
            "refs/remotes/origin/x",
            "gone",
            "",
        ).joinToString("\u0000")
        val gone = RefsParser.parse("$line\n".toByteArray()).single()
        assertTrue(gone.upstreamGone)
        assertNull(gone.ahead)
        assertFailsWith<GitParseException> { RefsParser.parse("refs/heads/x\u0000$sha\n".toByteArray()) }
    }
}
