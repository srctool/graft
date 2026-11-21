// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.graft.core.git.testing.RawGitCommand
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Git log's source: what the runner records, redacted, and how much it keeps. */
@ExtendWith(GitSandboxExtension::class)
class CommandLogTest {
    private fun runner(sandbox: GitSandbox, log: CommandLog) = ProcessGitRunner(
        GitRunnerConfig(
            executable = sandbox.gitExecutable,
            environment = sandbox.environment,
            launcher = sandbox.launcher,
            commandLog = log,
        ),
    )

    @Test
    fun `a run is recorded with its command, folder and exit code`(sandbox: GitSandbox) = runBlocking {
        val log = CommandLog()
        val repo = sandbox.repo { commit("one") { file("f.txt", "1") } }
        runner(sandbox, log).run(RawGitCommand(listOf("rev-list", "--count", "HEAD"), mutates = false), repo)
        val record = log.records.value.single()
        assertEquals("rev-list --count HEAD", record.command)
        assertEquals(repo, record.folder)
        assertEquals(0, record.exitCode)
        assertTrue(record.durationMillis >= 0)
    }

    @Test
    fun `a failing command is recorded with git's exit code`(sandbox: GitSandbox) = runBlocking {
        val log = CommandLog()
        val repo = sandbox.repo { commit("one") { file("f.txt", "1") } }
        runner(sandbox, log).run(RawGitCommand(listOf("rev-parse", "--verify", "nope^{commit}"), false), repo)
        assertNotNull(log.records.value.single().exitCode).let { assertTrue(it != 0) }
    }

    @Test
    fun `a command that could not start has no exit code`(sandbox: GitSandbox) = runBlocking {
        val log = CommandLog()
        val missing = ProcessGitRunner(
            GitRunnerConfig(executable = "graft-no-such-git", launcher = sandbox.launcher, commandLog = log),
        )
        missing.run(RawGitCommand(listOf("status"), mutates = false), sandbox.root)
        assertNull(log.records.value.single().exitCode)
    }

    @Test
    fun `secrets in a command never reach the log`() {
        val log = CommandLog()
        log.record(0, Paths.get("repo"), listOf("push", "https://user:hunter2@example.com/r.git"), 3, 0)
        val command = log.records.value.single().command
        assertEquals("push https://****@example.com/r.git", command)
    }

    @Test
    fun `only the last runs are kept, oldest dropped first`() {
        val log = CommandLog(capacity = 3)
        repeat(5) { log.record(it.toLong(), Paths.get("repo"), listOf("run", "$it"), 1, 0) }
        assertEquals(listOf("run 2", "run 3", "run 4"), log.records.value.map { it.command })
    }
}
