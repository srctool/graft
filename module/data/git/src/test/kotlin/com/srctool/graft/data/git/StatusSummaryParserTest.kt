// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.GitParseException
import com.srctool.graft.data.git.api.models.CloneProgress
import com.srctool.graft.data.git.api.models.Head
import com.srctool.graft.data.git.api.models.Upstream
import com.srctool.graft.data.git.implementation.source.local.ProgressParser
import com.srctool.graft.data.git.implementation.source.local.StatusSummaryParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** `status --porcelain=v2 --branch -z` headers, from fixtures recorded in `StatusRepos` (Task 3). */
class StatusSummaryParserTest {
    private fun parse(case: String) = StatusSummaryParser.parse(Fixtures.read("status", case))

    @Test
    fun `a clean branch with an upstream it's ahead of and behind`() {
        val s = parse("tracking")
        val head = assertIs<Head.Branch>(s.head)
        assertEquals("main", head.name)
        assertNotNull(head.commit)
        assertEquals(Upstream("base", ahead = 2, behind = 1), s.upstream)
        assertEquals(false, s.dirty)
    }

    @Test
    fun `untracked and staged files make it dirty`() {
        assertEquals(true, parse("untracked").dirty)
        assertEquals(true, parse("staged").dirty)
        assertNull(parse("untracked").upstream)
    }

    @Test
    fun `a detached HEAD has a commit and no upstream`() {
        val s = parse("detached")
        assertIs<Head.Detached>(s.head)
        assertNull(s.upstream)
    }

    @Test
    fun `an unborn branch has no commit yet`() {
        assertEquals(Head.Branch("main", null), parse("unborn").head)
    }

    @Test
    fun `an upstream whose ref was deleted is gone`() {
        assertEquals(Upstream("base", 0, 0, gone = true), parse("gone").upstream)
    }

    @Test
    fun `output without a branch header is a parse error`() {
        assertFailsWith<GitParseException> { StatusSummaryParser.parse("? a.txt\u0000".toByteArray()) }
        assertFailsWith<GitParseException> {
            StatusSummaryParser.parse("# branch.oid xyz\u0000# branch.head main\u0000".toByteArray())
        }
    }

    @Test
    fun `progress lines are read across carriage returns`() {
        val stderr =
            "Cloning into 'app'...\n" +
                "remote: Enumerating objects: 5, done.\n" +
                "remote: Counting objects:  40% (2/5)\rremote: Counting objects: 100% (5/5), done.\n" +
                "Receiving objects:  45% (692117/1507857), 364.78 MiB | 4.60 MiB/s\r" +
                "Receiving objects: 100% (1507857/1507857), 831.00 MiB | 5.00 MiB/s, done.\n" +
                "Resolving deltas: 100% (3/3), done.\n"
        val phases = ProgressParser.parse(stderr.byteInputStream()).toList()
        assertEquals(
            listOf(
                CloneProgress.Phase("Enumerating objects", null, 5, null),
                CloneProgress.Phase("Counting objects", 40, 2, 5),
                CloneProgress.Phase("Counting objects", 100, 5, 5),
                CloneProgress.Phase("Receiving objects", 45, 692117, 1507857),
                CloneProgress.Phase("Receiving objects", 100, 1507857, 1507857),
                CloneProgress.Phase("Resolving deltas", 100, 3, 3),
            ),
            phases,
        )
    }
}
