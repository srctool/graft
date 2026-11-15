// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

// stderr lines as git 2.38–2.54 print them.
class GitErrorsTest {
    @Test
    fun `recognizes lock, not-a-repo, auth and network failures`() {
        assertEquals(
            Kind.LOCK,
            classify(
                "fatal: Unable to create '/r/.git/index.lock': File exists.\n\nAnother git process seems to be running",
            ),
        )
        assertEquals(
            Kind.NOT_REPOSITORY,
            classify("fatal: not a git repository (or any of the parent directories): .git"),
        )
        assertEquals(
            Kind.AUTH,
            classify("git@github.com: Permission denied (publickey).\nfatal: Could not read from remote repository."),
        )
        assertEquals(
            Kind.AUTH,
            classify("fatal: could not read Username for 'https://github.com': terminal prompts disabled"),
        )
        assertEquals(
            Kind.NETWORK,
            classify("fatal: unable to access 'https://github.com/o/r/': Could not resolve host: github.com"),
        )
        assertEquals(Kind.NETWORK, classify("ssh: connect to host github.com port 22: Connection refused"))
    }

    @Test
    fun `leaves the rest to the exit code`() {
        assertNull(classify("fatal: Needed a single revision"))
        assertEquals(
            "GIT-R-REVP-EXIT",
            gitFailure(command("REVP"), emptyList(), 128, "fatal: Needed a single revision").code,
        )
    }

    @Test
    fun `a command's own rule wins`() {
        val push =
            object : GitInvocation by command("PUSH", mutates = true) {
                override fun failure(argv: List<String>, exitCode: Int, stderr: String): GitException? =
                    if ("[rejected]" in
                        stderr
                    ) {
                        GitRejectedException(function, argv, exitCode, stderr)
                    } else {
                        null
                    }
            }
        val e =
            gitFailure(
                push,
                listOf("git", "push"),
                1,
                " ! [rejected]        main -> main (fetch first)\nerror: failed to push some refs to 'origin'",
            )
        assertIs<GitRejectedException>(e)
        assertEquals("GIT-R-PUSH-REJ", e.code)
        assertEquals("error: failed to push some refs to 'origin'", e.summary)
        assertEquals(1, e.exitCode)
    }

    private fun command(function: String, mutates: Boolean = false): GitInvocation = object : GitInvocation {
        override val function = function
        override val args = listOf("x")
        override val mutates = mutates
    }
}
