// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.RawGitCommand
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `diffs` fixture repository: every case the details and patch parsers must handle (G1 Task 4), built the same
 * way each time, so commit ids are stable.
 *
 * - **base** (a root commit): text, a script, a file ending in a newline, a 10,000-line file, a binary file, a
 *   submodule gitlink, a file to delete;
 * - **change**: two-hunk edits, a rename with an edit, an exec bit, a lost final newline, edits deep in the big file,
 *   a binary change, a deletion, an addition, a moved gitlink, and trailers in the message;
 * - **latin1**: a file whose name and content are Latin-1 bytes, which only plumbing can write.
 */
object DiffRepo {
    const val TRAILER_AUTHOR = "Ann Lee <ann@example.invalid>"
    const val LATIN1_NAME = "café.txt"
    const val LATIN1_TEXT = "café au lait"

    class Built(val path: Path, val base: String, val change: String, val latin1: String)

    private fun numbered(count: Int, prefix: String) = (1..count).joinToString("") { "$prefix $it\n" }

    fun build(sandbox: GitSandbox): Built {
        val repo = sandbox.repo("diffs")
        fun git(vararg args: String): String = sandbox.git(repo, *args)
        fun writeBytes(path: String, bytes: ByteArray) {
            val file = repo.resolve(path)
            Files.createDirectories(file.parent)
            Files.write(file, bytes)
        }

        fun write(path: String, text: String) = writeBytes(path, text.toByteArray())

        write("a.txt", numbered(20, "line"))
        write("lib/Old.kt", numbered(20, "val x ="))
        write("run.sh", "echo hi\n")
        write("noeol.txt", "end\n")
        write("big.txt", numbered(10_000, "row"))
        writeBytes("pic.bin", byteArrayOf(0, 1, 2, 3, -1, 0))
        write("gone.txt", "bye\n")
        git("add", "--all")
        git("update-index", "--add", "--cacheinfo", "160000,1111111111111111111111111111111111111111,sub")
        git("commit", "--quiet", "-m", "Base")

        write("a.txt", numbered(20, "line").replace("line 2\n", "LINE 2\n").replace("line 18\n", "LINE 18\n"))
        git("mv", "lib/Old.kt", "lib/New.kt")
        write("lib/New.kt", numbered(20, "val x =") + "val y = 1\n")
        write("noeol.txt", "end")
        write(
            "big.txt",
            numbered(10_000, "row").replace("row 5000\n", "ROW 5000\n").replace("row 9000\n", "ROW 9000\n"),
        )
        writeBytes("pic.bin", byteArrayOf(0, 9, 2, 3, -1, 0))
        Files.delete(repo.resolve("gone.txt"))
        write("added.txt", "new\nfile\n")
        // `add --all` drops the gitlink (there's no submodule folder) and keeps the file system's mode, so both are set
        // in the index afterwards.
        git("add", "--all")
        git("update-index", "--add", "--cacheinfo", "160000,2222222222222222222222222222222222222222,sub")
        git("update-index", "--chmod=+x", "run.sh")
        git(
            "commit",
            "--quiet",
            "-m",
            "Change many things\n\nThe body explains why.\n\nCo-authored-by: $TRAILER_AUTHOR\n" +
                "Signed-off-by: Graft Test <test@graft.invalid>",
        )

        // Latin-1 bytes in a path can't pass through a Java string as an argument, so the tree is built from stdin.
        val latin1 = Charsets.ISO_8859_1
        val runner = sandbox.runner()
        runBlocking {
            val blob =
                runner.run(
                    RawGitCommand(listOf("hash-object", "-w", "--stdin")),
                    repo,
                    "$LATIN1_TEXT\n".toByteArray(latin1),
                )
                    .getOrThrow()
                    .trim()
            val tree = runner.run(RawBytesCommand(listOf("ls-tree", "-z", "HEAD")), repo).getOrThrow()
            val entry = "100644 blob $blob\t".toByteArray() + LATIN1_NAME.toByteArray(latin1) + byteArrayOf(0)
            val newTree =
                runner.run(RawGitCommand(listOf("mktree", "-z")), repo, tree + entry).getOrThrow().trim()
            val commit =
                runner.run(RawGitCommand(listOf("commit-tree", newTree, "-p", "HEAD", "-m", "Latin-1 file")), repo)
                    .getOrThrow()
                    .trim()
            sandbox.git(repo, "update-ref", "refs/heads/main", commit)
        }
        return Built(
            repo,
            base = git("rev-parse", "HEAD~2").trim(),
            change = git("rev-parse", "HEAD~1").trim(),
            latin1 = git("rev-parse", "HEAD").trim(),
        )
    }
}
