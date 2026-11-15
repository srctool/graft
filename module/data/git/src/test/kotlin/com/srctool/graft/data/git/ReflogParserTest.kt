// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.GitParseException
import com.srctool.graft.data.git.implementation.source.local.ReflogParser
import com.srctool.graft.data.git.implementation.source.local.RemotesParser
import com.srctool.graft.data.git.implementation.source.local.StashParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReflogParserTest {
    @Test
    fun `reflog entries, newest first, with git's message and the commit's subject`() {
        val entries = ReflogParser.parse(Fixtures.read("reflog", "history"))
        assertEquals(8, entries.size)
        assertEquals(
            listOf(
                "checkout: moving from topic to main",
                "commit: topic work",
                "checkout: moving from main to topic",
                "reset: moving to HEAD~1",
                "commit (amend): three, amended",
                "commit: three",
                "commit: two",
                "commit (initial): one",
            ),
            entries.map { it.message },
        )
        assertEquals("three, amended", entries[4].subject)
        assertEquals("Graft Test", entries[0].actor.name)
        assertEquals(1767225600L, entries[0].time)
        assertEquals(0, entries[0].offsetMinutes)
    }

    @Test
    fun `a deleted branch's commit stays in HEAD's reflog`() {
        val entries = ReflogParser.parse(Fixtures.read("reflog", "history"))
        assertEquals("topic work", entries[1].subject)
    }

    @Test
    fun `empty output is an empty reflog, and a cut record is an error`() {
        assertEquals(emptyList(), ReflogParser.parse(ByteArray(0)))
        val cut = "b721df1a0d40c914d9e6e753e9d66637e26a24c7\u0000HEAD@{1 +0000}\u0000msg\u0000".toByteArray()
        assertFailsWith<GitParseException> { ReflogParser.parse(cut) }
    }

    @Test
    fun `a negative zone offset`() {
        val fields =
            listOf("b721df1a0d40c914d9e6e753e9d66637e26a24c7", "HEAD@{1767225600 -0530}", "m", "n", "e", "s", "")
        val record = fields.joinToString("\u0000", postfix = "\u0000")
        assertEquals(-330, ReflogParser.parse(record.toByteArray()).single().offsetMinutes)
    }

    @Test
    fun `stashes with their index, base, branch and message`() {
        val stashes = StashParser.parse(Fixtures.read("stash", "list"))
        assertEquals(listOf(0, 1, 2), stashes.map { it.index })
        assertEquals(
            listOf("WIP on (no branch): 14b7b69 one", "On main: named", "WIP on main: 14b7b69 one"),
            stashes.map {
                it.message
            },
        )
        assertNull(stashes[0].branch)
        assertEquals("main", stashes[1].branch)
        assertEquals("main", stashes[2].branch)
        // The base is the stash's first parent, the commit it was made on; the untracked-files parent isn't it.
        assertTrue(stashes.all { it.base?.hex == "14b7b69408104a4e567fb0c95df09ed8924d85f7" })
        assertEquals(1767225600L, stashes[0].time)
    }

    @Test
    fun `remotes pair fetch and push URLs`() {
        val remotes = RemotesParser.parse(Fixtures.read("remote", "credentials").decodeToString()).associateBy {
            it.name
        }
        assertEquals(listOf("fork", "mirror", "origin", "plain"), remotes.keys.toList())
        assertEquals("https://example.invalid/plain.git", remotes.getValue("plain").fetchUrl)
        assertEquals(remotes.getValue("plain").fetchUrl, remotes.getValue("plain").pushUrl)
    }

    @Test
    fun `credentials in remote URLs are masked, an SSH user isn't`() {
        val remotes = RemotesParser.parse(Fixtures.read("remote", "credentials").decodeToString()).associateBy {
            it.name
        }
        assertEquals("https://****@example.invalid/graft.git", remotes.getValue("origin").fetchUrl)
        assertEquals("ssh://git@example.invalid/graft.git", remotes.getValue("origin").pushUrl)
        assertEquals("git@example.invalid:ann/graft.git", remotes.getValue("fork").fetchUrl)
        assertEquals("ssh://ann:****@example.invalid/graft.git", remotes.getValue("mirror").fetchUrl)
        assertFalse(remotes.values.any { "s3cret" in it.fetchUrl.orEmpty() || "s3cret" in it.pushUrl.orEmpty() })
    }

    @Test
    fun `an unreadable remote line fails without showing its credentials`() {
        val e =
            assertFailsWith<GitParseException> { RemotesParser.parse("origin https://ann:s3cret@example.invalid/x\n") }
        assertFalse("s3cret" in e.message.orEmpty())
    }
}
