// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.RawGitCommand
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

/** The repositories the `worktree` fixtures are recorded from, one per kind of change the status must read. */
object WorkingTreeRepos {
    fun build(sandbox: GitSandbox): Map<String, Path> = buildMap {
        put("mixed", mixed(sandbox))
        put("conflicts", conflicts(sandbox))
        put("submodule", submodule(sandbox))
        put(
            "unborn",
            sandbox.repo("unborn").also {
                Files.writeString(it.resolve("first.txt"), "first")
                sandbox.git(it, "add", "first.txt")
            },
        )
        put("clean", sandbox.repo("clean") { commit("one") { file("a.txt", "1") } })
        // Windows has no executable bit, so a mode change can only be built (and read live) where there is one.
        if (GitSandbox.isPosix) put("mode", mode(sandbox))
    }

    /** Staged, unstaged and both; a staged delete and an unstaged one; a rename; untracked; ignored. */
    private fun mixed(sandbox: GitSandbox): Path {
        val repo =
            sandbox.repo("mixed") {
                commit("one") {
                    file(".gitignore", "*.log\nbuild/\n")
                    file("staged.txt", "1\n")
                    file("unstaged.txt", "1\n")
                    file("both.txt", "1\n")
                    file("gone staged.txt", "1\n")
                    file("gone unstaged.txt", "1\n")
                    file("old name.txt", "same content\nsecond line\nthird line\n")
                    file("dir/inner.txt", "1\n")
                }
            }
        fun write(name: String, text: String) = Files.writeString(repo.resolve(name), text)
        write("staged.txt", "2\n")
        sandbox.git(repo, "add", "staged.txt")
        write("unstaged.txt", "2\n")
        write("both.txt", "2\n")
        sandbox.git(repo, "add", "both.txt")
        write("both.txt", "3\n")
        sandbox.git(repo, "rm", "--quiet", "gone staged.txt")
        Files.delete(repo.resolve("gone unstaged.txt"))
        sandbox.git(repo, "mv", "old name.txt", "new name.txt")
        write("added.txt", "new\n")
        sandbox.git(repo, "add", "added.txt")
        write("untracked file.txt", "u\n")
        Files.createDirectories(repo.resolve("fresh"))
        write("fresh/one.txt", "1\n")
        write("fresh/two.txt", "2\n")
        write("debug.log", "ignored\n")
        Files.createDirectories(repo.resolve("build"))
        write("build/out.bin", "ignored\n")
        return repo
    }

    /** The executable bit changed: staged for one file, and in the working tree only for another. */
    private fun mode(sandbox: GitSandbox): Path {
        val repo =
            sandbox.repo("mode") {
                commit("one") {
                    file("staged.sh", "#!/bin/sh\n")
                    file("unstaged.sh", "#!/bin/sh\n")
                }
            }
        val executable = java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")
        Files.setPosixFilePermissions(repo.resolve("staged.sh"), executable)
        sandbox.git(repo, "add", "staged.sh")
        Files.setPosixFilePermissions(repo.resolve("unstaged.sh"), executable)
        return repo
    }

    /** Both modified, both added, deleted by us: left unresolved by a real merge. */
    private fun conflicts(sandbox: GitSandbox): Path {
        val repo =
            sandbox.repo("conflicts") {
                commit("base") {
                    file("both.txt", "base\n")
                    file("deleted.txt", "base\n")
                }
                branch("theirs") {
                    commit("theirs") {
                        file("both.txt", "theirs\n")
                        file("deleted.txt", "theirs changed\n")
                        file("added.txt", "theirs\n")
                    }
                }
                commit("ours") {
                    file("both.txt", "ours\n")
                    delete("deleted.txt")
                    file("added.txt", "ours\n")
                }
            }
        // The merge stops on conflicts, which is the state to record; the failed Result is the point.
        runBlocking { sandbox.runner().run(RawGitCommand(listOf("merge", "--no-edit", "theirs")), repo) }
        return repo
    }

    /** A nested repository added as a gitlink, then moved on, dirtied and given an untracked file. */
    private fun submodule(sandbox: GitSandbox): Path {
        val repo = sandbox.repo("submodule") { commit("one") { file("a.txt", "1") } }
        val sub = repo.resolve("sub")
        sandbox.git(repo, "init", "--quiet", "--initial-branch=main", sub.toString())
        Files.writeString(sub.resolve("s.txt"), "1")
        sandbox.git(sub, "add", "s.txt")
        sandbox.git(sub, "commit", "--quiet", "-m", "sub one")
        sandbox.git(repo, "add", "sub")
        sandbox.git(repo, "commit", "--quiet", "-m", "add sub")
        Files.writeString(sub.resolve("s.txt"), "2")
        sandbox.git(sub, "commit", "--quiet", "-am", "sub two")
        Files.writeString(sub.resolve("s.txt"), "3")
        Files.writeString(sub.resolve("untracked.txt"), "u")
        return repo
    }
}
