// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.GitException
import com.srctool.graft.core.git.GitPatchException
import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.graft.data.git.api.models.ChangeSelection
import com.srctool.graft.data.git.api.models.FileSelection
import com.srctool.graft.data.git.api.models.FileSelection.Scope
import com.srctool.graft.data.git.api.models.LineRange
import com.srctool.graft.data.git.implementation.repositories.GitStagingRepository
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Staging, unstaging and discarding against real repositories, checked by what git itself then says. */
@ExtendWith(GitSandboxExtension::class)
class StagingRepositoryTest {
    private fun numbered(count: Int = 20, mark: (Int) -> String? = { null }) =
        (1..count).joinToString("") { (mark(it) ?: "l$it") + "\n" }

    private fun write(repo: Path, name: String, text: String) {
        val file = repo.resolve(name)
        Files.createDirectories(file.parent)
        Files.write(file, text.toByteArray(Charsets.ISO_8859_1))
    }

    private fun read(repo: Path, name: String) = String(Files.readAllBytes(repo.resolve(name)), Charsets.ISO_8859_1)

    /** The file in the index, byte for byte. */
    private fun staged(sandbox: GitSandbox, repo: Path, name: String) = sandbox.git(repo, "show", ":$name")

    private fun status(sandbox: GitSandbox, repo: Path) = sandbox.git(repo, "status", "--porcelain").trimEnd()

    private fun whole(vararg paths: String) = ChangeSelection(paths.map { FileSelection(it, Scope.WholeFile) })

    private fun hunks(path: String, vararg ids: Int, originalPath: String? = null) =
        ChangeSelection(listOf(FileSelection(path, Scope.Hunks(ids.toSet()), originalPath)))

    private fun lines(path: String, vararg ranges: LineRange) =
        ChangeSelection(listOf(FileSelection(path, Scope.Lines(ranges.toList()))))

    private fun repository(sandbox: GitSandbox) = GitStagingRepository(sandbox.runner())

    /** Two hunks, far enough apart not to merge: line 2 and line 18 changed. */
    private fun twoHunks(sandbox: GitSandbox): Path {
        val repo = sandbox.repo { commit("one") { file("f.txt", numbered()) } }
        write(repo, "f.txt", numbered { if (it == 2 || it == 18) "CHANGED $it" else null })
        return repo
    }

    @Test
    fun `staging a whole file takes new, changed and deleted ones`(sandbox: GitSandbox) = runBlocking {
        val repo =
            sandbox.repo {
                commit("one") {
                    file("changed.txt", "1\n")
                    file("gone.txt", "1\n")
                }
            }
        write(repo, "changed.txt", "2\n")
        write(repo, "new.txt", "n\n")
        Files.delete(repo.resolve("gone.txt"))
        repository(sandbox).stage(repo, whole("changed.txt", "new.txt", "gone.txt")).getOrThrow()
        assertEquals("M  changed.txt\nD  gone.txt\nA  new.txt", status(sandbox, repo))
    }

    @Test
    fun `a name with pattern characters stages only that file`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("seed.txt", "1\n") } }
        write(repo, "[a]*.txt", "x\n")
        write(repo, "a1.txt", "y\n")
        repository(sandbox).stage(repo, whole("[a]*.txt")).getOrThrow()
        assertEquals("A  [a]*.txt\n?? a1.txt", status(sandbox, repo))
    }

    @Test
    fun `a name with spaces and a non-ASCII letter stages whole and by hunk`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("café au lait.txt", numbered()) } }
        write(repo, "café au lait.txt", numbered { if (it == 2 || it == 18) "X$it" else null })
        val staging = repository(sandbox)
        staging.stage(repo, hunks("café au lait.txt", 0)).getOrThrow()
        assertTrue(staged(sandbox, repo, "café au lait.txt").contains("X2\n"))
        assertFalse(staged(sandbox, repo, "café au lait.txt").contains("X18\n"))
        staging.stage(repo, whole("café au lait.txt")).getOrThrow()
        assertEquals(read(repo, "café au lait.txt"), staged(sandbox, repo, "café au lait.txt"))
    }

    @Test
    fun `staging one hunk leaves the other in the working tree`(sandbox: GitSandbox) = runBlocking {
        val repo = twoHunks(sandbox)
        repository(sandbox).stage(repo, hunks("f.txt", 1)).getOrThrow()
        assertEquals(numbered { if (it == 18) "CHANGED 18" else null }, staged(sandbox, repo, "f.txt"))
        assertEquals("MM f.txt", status(sandbox, repo))
        // The working tree is exactly what it was.
        assertEquals(numbered { if (it == 2 || it == 18) "CHANGED $it" else null }, read(repo, "f.txt"))
    }

    @Test
    fun `staging single lines of a replaced block`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("f.txt", numbered()) } }
        write(repo, "f.txt", numbered { if (it in 5..7) "X$it" else null })
        // Hunk: 3 context, -l5 -l6 -l7 +X5 +X6 +X7, 3 context. Take the removal of l5 and the addition of X5.
        repository(sandbox).stage(repo, lines("f.txt", LineRange(0, 3), LineRange(0, 6))).getOrThrow()
        val expected = (1..4).map { "l$it" } + listOf("l6", "l7", "X5") + (8..20).map { "l$it" }
        assertEquals(expected.joinToString("") { "$it\n" }, staged(sandbox, repo, "f.txt"))
    }

    @Test
    fun `line endings survive a partial stage`(sandbox: GitSandbox) = runBlocking {
        val crlf = (1..20).joinToString("") { "l$it\r\n" }
        val repo = sandbox.repo { commit("one") { file("f.txt", crlf.toByteArray()) } }
        write(repo, "f.txt", (1..20).joinToString("") { (if (it == 2 || it == 18) "C$it" else "l$it") + "\r\n" })
        repository(sandbox).stage(repo, hunks("f.txt", 0)).getOrThrow()
        assertEquals(
            (1..20).joinToString("") {
                (
                    if (it ==
                        2
                    ) {
                        "C$it"
                    } else {
                        "l$it"
                    }
                    ) + "\r\n"
            },
            staged(sandbox, repo, "f.txt"),
        )
    }

    @Test
    fun `a file with no final newline stays that way`(sandbox: GitSandbox) = runBlocking {
        val old = "1\n2\n3\n4\n5\n6\n7\n8"
        val repo = sandbox.repo { commit("one") { file("f.txt", old) } }
        write(repo, "f.txt", "1\ntwo\n3\n4\n5\n6\n7\neight")
        // One hunk: the change to line 2, and the change to the last line, which has no newline.
        repository(sandbox).stage(repo, lines("f.txt", LineRange(0, 1, 2))).getOrThrow()
        assertEquals("1\ntwo\n3\n4\n5\n6\n7\n8", staged(sandbox, repo, "f.txt"))
        // Selecting the change at the end takes the removal and the addition together.
        repository(sandbox).stage(repo, hunks("f.txt", 0)).getOrThrow()
        assertEquals("1\ntwo\n3\n4\n5\n6\n7\neight", staged(sandbox, repo, "f.txt"))
    }

    @Test
    fun `lines of a new untracked file stage that part of it`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("seed.txt", "1\n") } }
        write(repo, "new.txt", "a\nb\nc\nd\n")
        repository(sandbox).stage(repo, lines("new.txt", LineRange(0, 0, 1))).getOrThrow()
        assertEquals("a\nb\n", staged(sandbox, repo, "new.txt"))
        assertEquals("AM new.txt", status(sandbox, repo))
        assertEquals("a\nb\nc\nd\n", read(repo, "new.txt"))
    }

    @Test
    fun `lines of a deleted file stage part of the deletion`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("f.txt", "a\nb\nc\nd\n") } }
        Files.delete(repo.resolve("f.txt"))
        repository(sandbox).stage(repo, lines("f.txt", LineRange(0, 0, 1))).getOrThrow()
        assertEquals("c\nd\n", staged(sandbox, repo, "f.txt"))
        repository(sandbox).stage(repo, hunks("f.txt", 0)).getOrThrow()
        assertEquals("D  f.txt", status(sandbox, repo))
    }

    @Test
    fun `a binary file stages whole, and has no hunks to pick`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("pic.bin", byteArrayOf(0, 1, 2)) } }
        Files.write(repo.resolve("pic.bin"), byteArrayOf(0, 9, 9, 9))
        val refused = repository(sandbox).stage(repo, hunks("pic.bin", 0))
        assertIs<Result.Failure>(refused)
        assertEquals("GIT-R-APLY-PAT", refused.error.code)
        assertEquals(" M pic.bin", status(sandbox, repo))
        repository(sandbox).stage(repo, whole("pic.bin")).getOrThrow()
        assertEquals("M  pic.bin", status(sandbox, repo))
    }

    @Test
    fun `a selection the file no longer has changes nothing`(sandbox: GitSandbox) = runBlocking {
        val repo = twoHunks(sandbox)
        val before = Files.readAllBytes(repo.resolve(".git/index")).toList()
        val refused = repository(sandbox).stage(repo, hunks("f.txt", 5))
        assertIs<Result.Failure>(refused)
        assertEquals("GIT-R-APLY-PAT", refused.error.code)
        assertIs<GitPatchException>(refused.error)
        assertEquals(before, Files.readAllBytes(repo.resolve(".git/index")).toList())
    }

    @Test
    fun `one stale file keeps the others from being staged`(sandbox: GitSandbox) = runBlocking {
        val repo = twoHunks(sandbox)
        write(repo, "other.txt", "x\n")
        val selection =
            ChangeSelection(
                listOf(FileSelection("other.txt", Scope.WholeFile), FileSelection("f.txt", Scope.Hunks(setOf(9)))),
            )
        assertIs<Result.Failure>(repository(sandbox).stage(repo, selection))
        assertEquals(" M f.txt\n?? other.txt", status(sandbox, repo))
    }

    @Test
    fun `unstaging a whole file works, also before the first commit`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo()
        write(repo, "first.txt", "1\n")
        sandbox.git(repo, "add", "first.txt")
        repository(sandbox).unstage(repo, whole("first.txt")).getOrThrow()
        assertEquals("?? first.txt", status(sandbox, repo))
    }

    @Test
    fun `unstaging a rename restores both names`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("old.txt", numbered()) } }
        sandbox.git(repo, "mv", "old.txt", "new.txt")
        val selection = ChangeSelection(listOf(FileSelection("new.txt", Scope.WholeFile, originalPath = "old.txt")))
        repository(sandbox).unstage(repo, selection).getOrThrow()
        assertEquals(" D old.txt\n?? new.txt", status(sandbox, repo))
    }

    @Test
    fun `unstaging one hunk puts that hunk back`(sandbox: GitSandbox) = runBlocking {
        val repo = twoHunks(sandbox)
        sandbox.git(repo, "add", "f.txt")
        repository(sandbox).unstage(repo, hunks("f.txt", 0)).getOrThrow()
        assertEquals(numbered { if (it == 18) "CHANGED 18" else null }, staged(sandbox, repo, "f.txt"))
        assertEquals("MM f.txt", status(sandbox, repo))
    }

    @Test
    fun `unstaging lines of a staged new file leaves the rest staged`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("seed.txt", "1\n") } }
        write(repo, "new.txt", "a\nb\nc\nd\n")
        sandbox.git(repo, "add", "new.txt")
        repository(sandbox).unstage(repo, lines("new.txt", LineRange(0, 2, 3))).getOrThrow()
        assertEquals("a\nb\n", staged(sandbox, repo, "new.txt"))
        repository(sandbox).unstage(repo, hunks("new.txt", 0)).getOrThrow()
        assertEquals("?? new.txt", status(sandbox, repo))
    }

    @Test
    fun `unstaging one hunk of a renamed and edited file keeps the rename`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("old.txt", numbered()) } }
        sandbox.git(repo, "mv", "old.txt", "new.txt")
        write(repo, "new.txt", numbered { if (it == 2 || it == 18) "CHANGED $it" else null })
        sandbox.git(repo, "add", "new.txt")
        repository(sandbox).unstage(repo, hunks("new.txt", 0, originalPath = "old.txt")).getOrThrow()
        assertEquals(numbered { if (it == 18) "CHANGED 18" else null }, staged(sandbox, repo, "new.txt"))
        assertTrue(status(sandbox, repo).startsWith("RM old.txt -> new.txt"), status(sandbox, repo))
    }

    @Test
    fun `discarding a hunk leaves the index and the other hunk alone`(sandbox: GitSandbox) = runBlocking {
        val repo = twoHunks(sandbox)
        repository(sandbox).discard(repo, hunks("f.txt", 0)).getOrThrow()
        assertEquals(numbered { if (it == 18) "CHANGED 18" else null }, read(repo, "f.txt"))
        assertEquals(" M f.txt", status(sandbox, repo))
        assertEquals(numbered(), staged(sandbox, repo, "f.txt"))
    }

    @Test
    fun `discarding lines of a hunk`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("f.txt", numbered()) } }
        write(repo, "f.txt", numbered { if (it in 5..7) "X$it" else null })
        // Discard the replacement of l5 only: its removal (index 3) and addition (index 6).
        repository(sandbox).discard(repo, lines("f.txt", LineRange(0, 3), LineRange(0, 6))).getOrThrow()
        val expected = (1..4).map { "l$it" } + listOf("l5", "X6", "X7") + (8..20).map { "l$it" }
        assertEquals(expected.joinToString("") { "$it\n" }, read(repo, "f.txt"))
    }

    @Test
    fun `discarding a whole tracked file restores it, a deleted one too`(sandbox: GitSandbox) = runBlocking {
        val repo =
            sandbox.repo {
                commit("one") {
                    file("a.txt", "1\n")
                    file("b.txt", "1\n")
                }
            }
        write(repo, "a.txt", "2\n")
        Files.delete(repo.resolve("b.txt"))
        repository(sandbox).discard(repo, whole("a.txt", "b.txt")).getOrThrow()
        assertEquals("", status(sandbox, repo))
    }

    @Test
    fun `discarding an untracked file deletes it, and nothing outside the working tree`(sandbox: GitSandbox) =
        runBlocking {
            val repo = sandbox.repo { commit("one") { file("seed.txt", "1\n") } }
            write(repo, "dir/new.txt", "x\n")
            val outside = sandbox.dir("outside").resolve("keep.txt").also { Files.writeString(it, "keep") }
            val staging = repository(sandbox)
            staging.discard(repo, whole("dir/new.txt")).getOrThrow()
            assertFalse(Files.exists(repo.resolve("dir/new.txt")))
            val escape = staging.discard(repo, whole("../../outside/keep.txt"))
            assertIs<Result.Failure>(escape)
            assertTrue(Files.exists(outside))
            assertIs<Result.Failure>(staging.discard(repo, whole(".git/HEAD")))
            assertTrue(Files.exists(repo.resolve(".git/HEAD")))
        }

    @Test
    fun `lines of an untracked file can be staged but not discarded`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("seed.txt", "1\n") } }
        write(repo, "new.txt", "a\nb\n")
        val refused = repository(sandbox).discard(repo, lines("new.txt", LineRange(0, 0)))
        assertIs<Result.Failure>(refused)
        assertEquals("a\nb\n", read(repo, "new.txt"))
    }

    @Test
    fun `a folder that is not a repository is a failure and compares no files`(sandbox: GitSandbox) = runBlocking {
        val plain = sandbox.dir("plain")
        write(plain, "a.txt", "1\n")
        write(plain, "b.txt", "2\n")
        val selection =
            ChangeSelection(listOf(FileSelection("a.txt", Scope.Hunks(setOf(0)), originalPath = "b.txt")))
        val result = repository(sandbox).stage(plain, selection)
        assertIs<Result.Failure>(result)
        assertIs<GitException>(result.error)
        Unit
    }

    @Test
    fun `an empty selection does nothing`(sandbox: GitSandbox) = runBlocking {
        val repo = twoHunks(sandbox)
        repository(sandbox).stage(repo, ChangeSelection(emptyList())).getOrThrow()
        repository(sandbox).stage(repo, hunks("f.txt")).getOrThrow()
        assertEquals(" M f.txt", status(sandbox, repo))
    }

    @Test
    fun `a mode change stages whole`(sandbox: GitSandbox) = runBlocking {
        org.junit.jupiter.api.Assumptions.assumeTrue(GitSandbox.isPosix, "executable bit")
        val repo = sandbox.repo { commit("one") { file("run.sh", "echo\n") } }
        Files.setPosixFilePermissions(
            repo.resolve("run.sh"),
            java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"),
        )
        repository(sandbox).stage(repo, whole("run.sh")).getOrThrow()
        assertEquals("M  run.sh", status(sandbox, repo))
        repository(sandbox).unstage(repo, whole("run.sh")).getOrThrow()
        assertEquals(" M run.sh", status(sandbox, repo))
    }

    @Test
    fun `a file with ten thousand hunks stages half of them`(sandbox: GitSandbox) = runBlocking {
        val rows = 100_000
        val repo = sandbox.repo { commit("one") { file("big.txt", numbered(rows)) } }
        // A change every ten lines is a hunk each: 3 lines of context each side never meet.
        write(repo, "big.txt", numbered(rows) { if (it % 10 == 0) "CHANGED $it" else null })
        val odd = (0 until 10_000 step 2).toList().toIntArray()
        val started = System.nanoTime()
        repository(sandbox).stage(repo, hunks("big.txt", *odd)).getOrThrow()
        val millis = (System.nanoTime() - started) / 1_000_000
        println("staging 5,000 of 10,000 hunks took $millis ms")
        assertEquals("5000\t5000\tbig.txt", sandbox.git(repo, "diff", "--cached", "--numstat").trim())
        assertEquals("5000\t5000\tbig.txt", sandbox.git(repo, "diff", "--numstat").trim())
    }
}
