// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.graft.core.exception.GitVersionException
import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.graft.core.git.testing.RawGitCommand
import com.srctool.sinew.models.errorOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Runner behavior that real git can't show on demand (hanging, misreporting its version, recording its environment),
 * with a fake `git` shell script. POSIX only; Windows gets the real-git tests.
 */
@ExtendWith(GitSandboxExtension::class)
class FakeGitTest {
    @BeforeEach
    fun posixOnly() = assumeTrue(GitSandbox.isPosix, "fake git scripts need a POSIX shell")

    // Writes resolve the repository first with rev-parse; the fakes answer it with their folder.
    private val answerRevParse = "case \"$*\" in *rev-parse*) pwd; exit 0;; esac"

    private fun alive(pidFile: Path): Boolean =
        ProcessHandle.of(Files.readString(pidFile).trim().toLong()).map { it.isAlive }.orElse(false)

    private suspend fun waitFor(file: Path) =
        withTimeout(10_000) { while (Files.notExists(file) || Files.size(file) == 0L) delay(20) }

    @Test
    fun `every process gets Graft's environment and global options`(sandbox: GitSandbox) = runBlocking {
        val out = sandbox.dir("out")
        val git = sandbox.fakeGit(
            "$answerRevParse\nenv > '$out/env'\nfor a in \"$@\"; do echo \"\$a\"; done > '$out/argv'",
        )
        val runner = sandbox.runner(git)

        runner.run(RawGitCommand(listOf("status"), mutates = false), sandbox.root)
        val env = Files.readAllLines(out.resolve("env"))
        listOf(
            "LC_ALL=C",
            "GIT_TERMINAL_PROMPT=0",
            "GIT_OPTIONAL_LOCKS=0",
            "GIT_EDITOR=false",
            "GIT_CONFIG_NOSYSTEM=1",
        )
            .forEach { assertTrue(it in env, "missing $it") }
        assertTrue("SSH_ASKPASS_REQUIRE=force" in env, "missing SSH_ASKPASS_REQUIRE=force")
        assertTrue("HOME=${sandbox.home}" in env, "HOME isn't the sandbox home")
        assertFalse(env.any { it.startsWith("SSH_AUTH_SOCK=") }, "SSH_AUTH_SOCK is set")
        assertEquals(
            listOf("--no-pager", "-c", "core.quotepath=false", "-c", "color.ui=never", "status"),
            Files.readAllLines(out.resolve("argv")),
        )

        runner.run(RawGitCommand(listOf("commit"), mutates = true), sandbox.root)
        assertFalse(
            Files.readAllLines(out.resolve("env")).any {
                it.startsWith("GIT_OPTIONAL_LOCKS=")
            },
            "GIT_OPTIONAL_LOCKS is set for a write",
        )
    }

    @Test
    fun `cancelling a run kills git and its children`(sandbox: GitSandbox) = runBlocking {
        val out = sandbox.dir("out")
        val git = sandbox.fakeGit("sleep 60 &\necho $! > '$out/child'\necho $$ > '$out/parent'\nwait")
        val job =
            launch(Dispatchers.Default) {
                sandbox.runner(git).run(RawGitCommand(listOf("fetch"), mutates = false), sandbox.root)
            }
        waitFor(out.resolve("parent"))
        waitFor(out.resolve("child"))
        withTimeout(5_000) { job.cancelAndJoin() }
        withTimeout(5_000) { while (alive(out.resolve("parent")) || alive(out.resolve("child"))) delay(20) }
    }

    @Test
    fun `stopping a stream kills git`(sandbox: GitSandbox) = runBlocking {
        val out = sandbox.dir("out")
        val git = sandbox.fakeGit("echo $$ > '$out/parent'\nwhile :; do printf 'x\\n'; done")
        val lines =
            object : StreamingGitCommand<String>() {
                override val function = "LOG"
                override val args = listOf("log")

                override fun parse(stdout: InputStream) = stdout.bufferedReader().lineSequence()
            }
        assertEquals(
            List(5) {
                "x"
            },
            withTimeout(10_000) { sandbox.runner(git).stream(lines, sandbox.root).take(5).toList() },
        )
        withTimeout(5_000) { while (alive(out.resolve("parent"))) delay(20) }
    }

    @Test
    fun `an old git is refused`(sandbox: GitSandbox) = runBlocking {
        val error = sandbox.runner(
            sandbox.fakeGit("echo 'git version 2.37.9'"),
        ).checkVersion(sandbox.root).errorOrNull
        assertIs<GitVersionException>(error)
        assertEquals("GIT-R-VERS-VER", error.code)
        assertContains(error.message, "2.37.9")
    }

    @Test
    fun `an unreadable version is a PARSE error`(sandbox: GitSandbox) = runBlocking {
        val error = sandbox.runner(sandbox.fakeGit("echo 'not git'")).checkVersion(sandbox.root).errorOrNull
        assertEquals("GIT-R-VERS-DF", error?.code)
    }

    @Test
    fun `a missing git is NF`(sandbox: GitSandbox) = runBlocking {
        val error = sandbox.runner(
            sandbox.root.resolve("no-such-git").toString(),
        ).checkVersion(sandbox.root).errorOrNull
        assertEquals("GIT-R-VERS-NF", error?.code)
    }

    @Test
    fun `writes wait for each other, reads don't`(sandbox: GitSandbox) = runBlocking(Dispatchers.Default) {
        val log = sandbox.root.resolve("log")
        val git = sandbox.fakeGit("$answerRevParse\necho start >> '$log'\nsleep 0.3\necho end >> '$log'")
        val runner = sandbox.runner(git)

        (1..3).map {
            async { runner.run(RawGitCommand(listOf("commit"), mutates = true), sandbox.root) }
        }.awaitAll()
        assertEquals(List(3) { listOf("start", "end") }.flatten(), Files.readAllLines(log))

        Files.delete(log)
        (1..3).map {
            async { runner.run(RawGitCommand(listOf("status"), mutates = false), sandbox.root) }
        }.awaitAll()
        assertEquals(listOf("start", "start"), Files.readAllLines(log).take(2))
    }
}
