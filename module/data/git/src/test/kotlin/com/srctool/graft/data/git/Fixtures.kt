// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.GitCommand
import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.RawGitCommand
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Recorded git output under the repository's `fixtures/git/` (Testing Strategy §3.3). */
object Fixtures {
    private val root: Path = Paths.get(
        System.getProperty("graft.fixtures") ?: error("graft.fixtures isn't set; run through Gradle"),
    )

    val recording: Boolean = System.getProperty("graft.recordFixtures") == "true"

    fun path(command: String, case: String): Path = root.resolve("git").resolve(command).resolve("$case.bin")

    fun read(command: String, case: String): ByteArray = Files.readAllBytes(path(command, case))

    fun write(command: String, case: String, bytes: ByteArray) {
        val file = path(command, case)
        Files.createDirectories(file.parent)
        Files.write(file, bytes)
    }
}

/** `git <args>` returning raw stdout bytes, for recording fixtures exactly as git wrote them. */
class RawBytesCommand(override val args: List<String>) : GitCommand<ByteArray>() {
    override val function: String = "TEST"

    override fun parse(stdout: ByteArray): ByteArray = stdout
}

/**
 * The `graph` fixture repository: every case the log and refs parsers must handle, built the same way each time.
 * Commit dates and identities come from the sandbox, so hashes are identical on every machine and Git version.
 */
object GraphRepo {
    const val CRAFTED_AUTHOR = "José Café"
    const val CRAFTED_SUBJECT = "café au lait"

    fun build(sandbox: GitSandbox): Path = sandbox.repo("graph") {
        commit("init") { file("a.txt", "1") }
        tag("v0-light")
        branch("feature") { commit("feature work") { file("b.txt", "f") } }
        commit("main 2") { file("a.txt", "2") }
        tag("v1", "Release 1")
        merge("feature")

        // A second root, merged in.
        git("switch", "--quiet", "--orphan", "docs")
        commit("docs root") { file("docs.md", "d") }
        git("switch", "--quiet", "main")
        git("merge", "--quiet", "--no-ff", "--allow-unrelated-histories", "-m", "Merge docs", "docs")

        // An octopus merge of three branches. Built from plumbing, not `git merge`: the octopus strategy is a shell
        // script, and on Windows CI its MSYS shell failed to start (exit 0xC0000142) or hung.
        val octopus = listOf("a", "b", "c")
        octopus.forEach { b -> branch(b) { commit("work $b") { file("o$b.txt", b) } } }
        octopus.forEach { b -> WorkingFile(path).write("o$b.txt", b) }
        git("add", "--all")
        val merged = git("write-tree").trim()
        val parents = (listOf("HEAD") + octopus).flatMap { listOf("-p", git("rev-parse", it).trim()) }
        git(
            "update-ref",
            "refs/heads/main",
            git("commit-tree", merged, *parents.toTypedArray(), "-m", "Octopus").trim(),
        )

        git("commit", "--quiet", "--allow-empty", "--allow-empty-message", "-m", "")

        // A commit whose author and message are Latin-1 bytes with no encoding header, as old tools wrote them.
        val tree = git("rev-parse", "HEAD^{tree}").trim()
        val parent = git("rev-parse", "HEAD").trim()
        val latin1 = Charsets.ISO_8859_1
        val body =
            "tree $tree\nparent $parent\n".toByteArray() +
                "author $CRAFTED_AUTHOR <jose@graft.invalid> 1767225600 +0000\n".toByteArray(latin1) +
                "committer Graft Test <test@graft.invalid> 1767225600 +0000\n\n".toByteArray() +
                "$CRAFTED_SUBJECT\n".toByteArray(latin1)
        val crafted =
            runBlocking {
                sandbox.runner().run(
                    RawGitCommand(listOf("hash-object", "-t", "commit", "-w", "--stdin")),
                    path,
                    body,
                ).getOrThrow().trim()
            }
        git("update-ref", "refs/heads/main", crafted)

        // A remote with an upstream two commits behind, and its HEAD symref.
        git("remote", "add", "origin", "https://example.invalid/graft.git")
        git("update-ref", "refs/remotes/origin/main", git("rev-parse", "HEAD~2").trim())
        git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")
        git("config", "branch.main.remote", "origin")
        git("config", "branch.main.merge", "refs/heads/main")

        // A stash and one of Graft's own refs; the log leaves both out.
        git("update-ref", "refs/graft/shelves/test", "HEAD")
        WorkingFile(path).write("a.txt", "dirty")
        git("stash", "push", "--quiet", "-m", "wip")
    }

    private class WorkingFile(private val root: Path) {
        fun write(relative: String, content: String) {
            Files.writeString(root.resolve(relative), content)
        }
    }
}
