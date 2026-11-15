// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.exception

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RedactionTest {
    // Obvious fakes: right shape, no real secret.
    private val fakeGhp = "ghp_" + "A".repeat(36)
    private val fakePat = "github_pat_" + "B".repeat(40)

    @Test
    fun `removes credentials from URLs`() {
        assertEquals(
            "fatal: unable to access 'https://****@github.com/o/r.git/'",
            Redaction.redact("fatal: unable to access 'https://user:$fakeGhp@github.com/o/r.git/'"),
        )
        assertEquals("ssh://****@host/repo", Redaction.redact("ssh://git@host/repo"))
    }

    @Test
    fun `removes tokens and auth headers`() {
        assertFalse(fakeGhp in Redaction.redact("token is $fakeGhp"))
        assertFalse(fakePat in Redaction.redact(fakePat))
        assertEquals("Authorization: ****", Redaction.redact("Authorization: Bearer abc.def.ghi"))
        assertEquals("Bearer ****", Redaction.redact("Bearer abcdefghijkl"))
    }

    @Test
    fun `leaves ordinary text alone`() {
        val text = "error: pathspec 'src/token.kt' did not match any file(s) known to git"
        assertEquals(text, Redaction.redact(text))
    }
}
