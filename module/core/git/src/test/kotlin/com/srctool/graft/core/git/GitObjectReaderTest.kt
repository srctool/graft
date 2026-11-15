// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** One long-lived `git cat-file --batch` (Commit Graph: full messages and file contents on demand). Real git. */
@ExtendWith(GitSandboxExtension::class)
class GitObjectReaderTest {
    private fun GitSandbox.sample(): Path = repo {
        commit("first line\n\nThe body of the message.") {
            file("a.txt", "hello\n")
            file("bin.dat", byteArrayOf(0, 1, 2, -1, 0, 10))
        }
    }

    @Test
    fun `reads blobs by revision and path, and commits by name`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.sample()
        sandbox.runner().objectReader(repo).use { reader ->
            val blob = assertIs<GitObject.Found>(reader.read("HEAD:a.txt").getOrThrow())
            assertEquals("blob", blob.type)
            assertEquals("hello\n", blob.bytes.toString(Charsets.UTF_8))
            assertEquals(40, blob.id.length)

            val commit = assertIs<GitObject.Found>(reader.read("HEAD").getOrThrow())
            assertEquals("commit", commit.type)
            assertTrue("The body of the message." in commit.bytes.toString(Charsets.UTF_8))

            val binary = assertIs<GitObject.Found>(reader.read("HEAD:bin.dat").getOrThrow())
            assertContentEquals(byteArrayOf(0, 1, 2, -1, 0, 10), binary.bytes)
        }
    }

    @Test
    fun `a missing object is Missing, and the reader keeps working`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.sample()
        sandbox.runner().objectReader(repo).use { reader ->
            assertEquals(GitObject.Missing, reader.read("HEAD:nope.txt").getOrThrow())
            assertEquals(GitObject.Missing, reader.read("0000000000000000000000000000000000000001").getOrThrow())
            assertIs<GitObject.Found>(reader.read("HEAD:a.txt").getOrThrow())
            assertEquals(1, (reader as ProcessGitObjectReader).starts)
        }
    }

    @Test
    fun `one process serves many reads, in parallel too`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("many") { repeat(30) { file("f$it.txt", "content $it\n") } } }
        sandbox.runner().objectReader(repo).use { reader ->
            val texts =
                (0 until 30)
                    .map { i ->
                        async {
                            (reader.read("HEAD:f$i.txt").getOrThrow() as GitObject.Found).bytes.toString(Charsets.UTF_8)
                        }
                    }
                    .awaitAll()
            assertEquals((0 until 30).map { "content $it\n" }, texts)
            assertEquals(1, (reader as ProcessGitObjectReader).starts)
        }
    }

    @Test
    fun `an object over the limit isn't read in, and the next read restarts git`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo {
            commit("big") {
                file("big.txt", "x".repeat(100_000))
                file("small.txt", "s")
            }
        }
        sandbox.runner().objectReader(repo).use { reader ->
            val big = assertIs<GitObject.TooLarge>(reader.read("HEAD:big.txt", maxBytes = 1_000).getOrThrow())
            assertEquals(100_000L, big.size)
            assertIs<GitObject.Found>(reader.read("HEAD:small.txt").getOrThrow())
            assertEquals(2, (reader as ProcessGitObjectReader).starts)
        }
    }

    @Test
    fun `a cancelled read leaves the reader usable`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo {
            commit("big") {
                file("big.txt", "y".repeat(5_000_000))
                file("small.txt", "s")
            }
        }
        sandbox.runner().objectReader(repo).use { reader ->
            withTimeoutOrNull(1) { reader.read("HEAD:big.txt") }
            val small = assertIs<GitObject.Found>(reader.read("HEAD:small.txt").getOrThrow())
            assertEquals("s", small.bytes.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `a spec with a newline is refused instead of breaking the batch`(sandbox: GitSandbox) = runBlocking<Unit> {
        val repo = sandbox.sample()
        sandbox.runner().objectReader(repo).use { reader ->
            assertEquals(
                "GIT-R-CATF-GEN",
                reader.read("HEAD:a\nb").let {
                    (it as com.srctool.sinew.models.Result.Failure).error.code
                },
            )
            assertIs<GitObject.Found>(reader.read("HEAD:a.txt").getOrThrow())
        }
    }

    @Test
    fun `outside a repository the read fails with git's reason`(sandbox: GitSandbox) = runBlocking {
        sandbox.runner().objectReader(sandbox.dir("plain")).use { reader ->
            val failure = reader.read("HEAD") as com.srctool.sinew.models.Result.Failure
            assertEquals("GIT-R-CATF-NF", failure.error.code)
        }
    }

    @Test
    fun `closing stops git`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.sample()
        val reader = sandbox.runner().objectReader(repo) as ProcessGitObjectReader
        reader.read("HEAD:a.txt").getOrThrow()
        val process = reader.currentProcess!!
        reader.close()
        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) || !process.isAlive)
        assertTrue(Files.isDirectory(repo))
    }
}
