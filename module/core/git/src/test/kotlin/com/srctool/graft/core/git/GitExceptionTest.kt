// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.sinew.models.ErrorMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitExceptionTest {
    // Obvious fakes: right shape, no real secret.
    private val fakeToken = "ghp_" + "A".repeat(36)

    @Test
    fun `codes read MODULE-LAYER-FUNCTION-TYPE with Sinew's repository layer`() {
        assertEquals("GIT-R-PUSH-REJ", GitRejectedException("PUSH", listOf("git", "push"), 1, "").code)
        assertEquals("GIT-R-VERS-VER", GitVersionException("VERS", listOf("git", "--version"), "2.37.9", "2.38.0").code)
        assertEquals("GIT-R-ADD-LOCK", GitLockedException("ADD", emptyList(), 128, "").code)
    }

    @Test
    fun `argv and stderr are redacted when created`() {
        val e =
            GitAuthException(
                "PUSH",
                listOf("git", "push", "https://x:$fakeToken@github.com/o/r"),
                128,
                "remote: $fakeToken",
            )
        assertFalse(e.argv.any { fakeToken in it })
        assertFalse(fakeToken in e.stderr)
    }

    @Test
    fun `summary is git's first fatal or error line`() {
        val e = GitExitException("REVP", emptyList(), 128, "hint: x\nfatal: Needed a single revision\nerror: more\n")
        assertEquals("fatal: Needed a single revision", e.summary)
        assertEquals("", GitExitException("REVP", emptyList(), 1, "just noise").summary)
    }

    @Test
    fun `messages are localizable keys with an English fallback`() {
        val e = GitVersionException("VERS", emptyList(), "2.37.9", "2.38.0")
        val m = assertIs<ErrorMessage.Local>(e.errorMessage)
        assertEquals("git.error.version", m.key)
        assertEquals("Git 2.37.9 is too old; Graft needs Git 2.38.0 or newer.", e.message)
        assertEquals(
            "Git isn't installed, or Graft can't start it.",
            GitNotFoundException("VERS", emptyList(), null, "").message,
        )
        assertEquals("This folder isn't a Git repository.", GitNotFoundException("LOG", emptyList(), 128, "").message)
    }

    @Test
    fun `lock, network and timeout failures are retryable`() {
        assertTrue(GitLockedException("ADD", emptyList(), 128, "").retryable)
        assertTrue(GitNetworkException("FTCH", emptyList(), 128, "").retryable)
        assertFalse(GitRejectedException("PUSH", emptyList(), 1, "").retryable)
    }

    @Test
    fun `function codes are 2 to 4 letters`() {
        assertFailsWith<IllegalArgumentException> { GitExitException("CLONE", emptyList(), 1, "") }
        assertFailsWith<IllegalArgumentException> { GitExitException("log", emptyList(), 1, "") }
    }
}
