// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.graft.core.git.testing.RawGitCommand
import com.srctool.sinew.exception.UnexpectedException
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.errorOrNull
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.ExtendWith
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Real git, in a sandbox. Runs on the CI's latest Git and on Git 2.38 (`git-min`). */
@ExtendWith(GitSandboxExtension::class)
class ProcessGitRunnerTest {
    private fun read(vararg args: String) = RawGitCommand(args.toList(), mutates = false)

    private fun write(vararg args: String) = RawGitCommand(args.toList(), mutates = true)

    /** `git log --format=%H -z`, streamed one hash at a time. */
    private class Hashes(extra: List<String> = emptyList()) : StreamingGitCommand<String>() {
        override val function = "LOG"
        override val args = listOf("log", "--format=%H", "-z") + extra

        override fun parse(stdout: InputStream): Sequence<String> = sequence {
            val buffer = StringBuilder()
            while (true) {
                val b = stdout.read()
                if (b < 0) break
                if (b == 0) {
                    yield(buffer.toString())
                    buffer.clear()
                } else {
                    buffer.append(b.toChar())
                }
            }
        }
    }

    /** `git clone --progress`, reading git's progress lines from stderr. */
    private class CloneProgress(url: String, target: String) : StreamingGitCommand<String>() {
        override val function = "CLON"
        override val args = listOf("clone", "--progress", url, target)
        override val output = GitOutput.STDERR

        override fun parse(stdout: InputStream): Sequence<String> =
            stdout.bufferedReader().lineSequence().flatMap { it.split('\r') }.filter { it.isNotBlank() }
    }

    private fun GitSandbox.history(commits: Int): Path =
        repo { repeat(commits) { i -> commit("commit $i") { file("f.txt", "$i") } } }

    @Test
    fun `the installed git is supported`(sandbox: GitSandbox) = runBlocking {
        val version = sandbox.runner().checkVersion(sandbox.root).getOrThrow()
        assertTrue(version >= GitVersion.MINIMUM, "git $version")
    }

    @Test
    fun `runs in a repository`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.history(3)
        assertEquals("3", sandbox.runner().run(read("rev-list", "--count", "HEAD"), repo).getOrThrow().trim())
    }

    @Test
    fun `passes Graft's global options`(sandbox: GitSandbox) = runBlocking {
        val runner = sandbox.runner()
        assertEquals(
            "false",
            runner.run(read("config", "--get", "core.quotepath"), sandbox.root).getOrThrow().trim(),
        )
        assertEquals("never", runner.run(read("config", "--get", "color.ui"), sandbox.root).getOrThrow().trim())
        assertEquals("false", runner.run(read("var", "GIT_EDITOR"), sandbox.root).getOrThrow().trim())
    }

    @Test
    fun `the sandbox hides the developer's own config`(sandbox: GitSandbox) = runBlocking {
        val origins = sandbox.runner().run(read("config", "--list", "--show-origin"), sandbox.root).getOrThrow()
        // Only command-line options remain; no system, global or XDG file is read.
        assertTrue(origins.lines().filter { it.isNotBlank() }.all { it.startsWith("command line:") }, origins)
    }

    @Test
    fun `writes stdin`(sandbox: GitSandbox) = runBlocking {
        val hash = sandbox.runner().run(read("hash-object", "--stdin"), sandbox.root, "hello\n".toByteArray())
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", hash.getOrThrow().trim())
    }

    @Test
    fun `a failure keeps git's stderr and exit code`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.history(1)
        val error =
            assertIs<GitExitException>(
                sandbox.runner().run(read("rev-parse", "--verify", "nope^{commit}"), repo).errorOrNull,
            )
        assertEquals("GIT-R-TEST-EXIT", error.code)
        assertEquals(128, error.exitCode)
        assertContains(error.stderr, "Needed a single revision")
        assertTrue(error.summary.startsWith("fatal:"), error.summary)
        assertEquals("rev-parse", error.argv[6])
    }

    /** `merge-base --is-ancestor HEAD HEAD~1`: exit 1 means "no", which only some commands treat as an answer. */
    private class IsAncestor(accepts: Set<Int>) : GitCommand<Boolean>() {
        override val function = "ANCS"
        override val args = listOf("merge-base", "--is-ancestor", "HEAD", "HEAD~1")
        override val successExitCodes = accepts

        override fun parse(stdout: ByteArray) = true
    }

    @Test
    fun `a command can treat another exit code as an answer`(sandbox: GitSandbox) = runBlocking<Unit> {
        val repo = sandbox.history(2)
        assertEquals(true, sandbox.runner().run(IsAncestor(setOf(0, 1)), repo).getOrThrow())
        val error = assertIs<GitExitException>(sandbox.runner().run(IsAncestor(setOf(0)), repo).errorOrNull)
        assertEquals(1, error.exitCode)
    }

    @Test
    fun `outside a repository is not found`(sandbox: GitSandbox) = runBlocking {
        val error = sandbox.runner().run(read("rev-parse", "HEAD"), sandbox.dir("empty")).errorOrNull
        assertEquals("GIT-R-TEST-NF", error?.code)
    }

    @Test
    fun `a held index lock is reported as LOCK`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.history(1)
        Files.writeString(repo.resolve("f.txt"), "changed")
        Files.createFile(repo.resolve(".git/index.lock"))
        val error = sandbox.runner().run(write("add", "f.txt"), repo).errorOrNull
        assertEquals("GIT-R-TEST-LOCK", error?.code)
    }

    @Test
    fun `an unparsable output is a PARSE error`(sandbox: GitSandbox) = runBlocking {
        val cmd =
            object : GitCommand<Int>() {
                override val function = "VERS"
                override val args = listOf("--version")

                override fun parse(stdout: ByteArray): Int = throw GitParseException("not a number")
            }
        assertEquals("GIT-R-VERS-DF", sandbox.runner().run(cmd, sandbox.root).errorOrNull?.code)
    }

    @Test
    fun `streams output`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.history(50)
        val hashes = sandbox.runner().stream(Hashes(), repo).toList()
        assertEquals(50, hashes.size)
        assertTrue(hashes.all { it.length == 40 })
    }

    @Test
    fun `stopping a stream early stops git`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.history(50)
        assertEquals(3, sandbox.runner().stream(Hashes(), repo).take(3).toList().size)
    }

    @Test
    fun `a failing stream throws its error`(sandbox: GitSandbox) {
        val e =
            assertFailsWith<GitNotFoundException> {
                runBlocking { sandbox.runner().stream(Hashes(), sandbox.dir("empty")).toList() }
            }
        assertEquals("GIT-R-LOG-NF", e.code)
    }

    @Test
    fun `a command can stream git's stderr, as clone reports progress there`(sandbox: GitSandbox) = runBlocking {
        val source = sandbox.history(20)
        val lines = sandbox.runner().stream(CloneProgress(source.toUri().toString(), "copy"), sandbox.root).toList()
        assertTrue(lines.any { it.startsWith("Cloning into") }, lines.toString())
        assertTrue(lines.any { "objects" in it && "%" in it }, lines.toString())
        assertTrue(Files.isDirectory(sandbox.root.resolve("copy/.git")))
    }

    @Test
    fun `a failing stderr stream still keeps git's error`(sandbox: GitSandbox) {
        val missing = sandbox.root.resolve("nowhere").toUri().toString()
        val e =
            assertFailsWith<GitException> {
                runBlocking { sandbox.runner().stream(CloneProgress(missing, "copy2"), sandbox.root).toList() }
            }
        assertEquals("CLON", e.function)
        assertContains(e.stderr, "fatal:")
    }

    @Test
    fun `parallel writes to one repository all succeed`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.history(1)
        val runner = sandbox.runner()
        // Without the write queue these race for index.lock and some fail.
        val results =
            (1..8)
                .map { i ->
                    async {
                        Files.writeString(repo.resolve("p$i.txt"), "$i")
                        runner.run(write("add", "p$i.txt"), repo)
                    }
                }.awaitAll()
        assertTrue(results.all { it is Result.Success }, results.toString())
        assertEquals(
            "9",
            runner.run(read("ls-files"), repo).getOrThrow().lines().count {
                it.isNotBlank()
            }.toString(),
        )
    }

    @Test
    fun `the sandbox refuses to run git outside itself`(sandbox: GitSandbox) {
        val outside = Files.createTempDirectory("graft-outside-")
        try {
            // The guard turns the refused start into a failed result; the sandbox also records it, so no test can ignore it.
            val error =
                assertIs<UnexpectedException>(
                    runBlocking {
                        sandbox.runner().run(read("--version"), outside)
                    }.errorOrNull,
                )
            assertIs<AssertionError>(error.cause)
            assertEquals(1, sandbox.violations.size)
            sandbox.violations.clear()
        } finally {
            outside.toFile().deleteRecursively()
        }
    }
}
