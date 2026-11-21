// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A command's own variables (`GIT_INDEX_FILE`) join the environment, and never undo Graft's settings. */
class PerCommandEnvironmentTest {
    private class Env(override val environment: Map<String, String?>) : GitCommand<Unit>() {
        override val function = "TEST"
        override val args = listOf("status")

        override fun parse(stdout: ByteArray) = Unit
    }

    private fun runner(inherited: Map<String, String?> = emptyMap()) =
        ProcessGitRunner(GitRunnerConfig(environment = inherited))

    @Test
    fun `a command's variables are added`() {
        val env = runner().environment(Env(mapOf("GIT_INDEX_FILE" to "/tmp/x/index")))
        assertEquals("/tmp/x/index", env["GIT_INDEX_FILE"])
    }

    @Test
    fun `a command can remove a variable, and its values win over the config's`() {
        val config = runner(mapOf("GIT_AUTHOR_NAME" to "config", "GIT_DIR" to "/elsewhere"))
        val env = config.environment(Env(mapOf("GIT_AUTHOR_NAME" to "command", "GIT_DIR" to null)))
        assertEquals("command", env["GIT_AUTHOR_NAME"])
        assertNull(env["GIT_DIR"])
    }

    @Test
    fun `a command cannot undo Graft's own settings`() {
        val env = runner().environment(
            Env(
                mapOf(
                    "LC_ALL" to "de_DE",
                    "GIT_TERMINAL_PROMPT" to "1",
                    "GIT_EDITOR" to "vim",
                ),
            ),
        )
        assertEquals("C", env["LC_ALL"])
        assertEquals("0", env["GIT_TERMINAL_PROMPT"])
        assertEquals("false", env["GIT_EDITOR"])
    }

    @Test
    fun `commands without variables behave as before`() {
        val env = runner().environment(Env(emptyMap()))
        assertEquals("0", env["GIT_OPTIONAL_LOCKS"])
    }
}
