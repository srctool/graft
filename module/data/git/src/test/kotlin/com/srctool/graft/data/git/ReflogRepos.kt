// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.testing.GitSandbox
import java.nio.file.Files
import java.nio.file.Path

/** The repositories the reflog, stash and remote fixtures and tests use. */
object ReflogRepos {
    /** Commits, an amend, a reset, a checkout and a deleted branch: HEAD's reflog has one entry for each move. */
    fun history(sandbox: GitSandbox): Path = sandbox.repo("history") {
        commit("one") { file("a.txt", "1") }
        commit("two") { file("a.txt", "2") }
        commit("three") { file("a.txt", "3") }
        git("commit", "--quiet", "--amend", "-m", "three, amended")
        git("reset", "--quiet", "--hard", "HEAD~1")
        branch("topic") { commit("topic work") { file("t.txt", "t") } }
        git("branch", "--quiet", "-D", "topic")
    }

    /** Two stashes, one named and one made on a detached HEAD, and one with untracked files. */
    fun stashes(sandbox: GitSandbox): Path = sandbox.repo("stashes") {
        commit("one") { file("a.txt", "1") }
    }.also { repo ->
        fun write(name: String, text: String) = Files.writeString(repo.resolve(name), text)
        write("a.txt", "wip")
        sandbox.git(repo, "stash", "push", "--quiet")
        write("a.txt", "named")
        write("new.txt", "untracked")
        sandbox.git(repo, "stash", "push", "--quiet", "--include-untracked", "-m", "named")
        sandbox.git(repo, "switch", "--quiet", "--detach", "HEAD")
        write("a.txt", "detached")
        sandbox.git(repo, "stash", "push", "--quiet")
        sandbox.git(repo, "switch", "--quiet", "main")
    }

    /** Remotes with and without credentials in their URLs; the password is fake. */
    fun remotes(sandbox: GitSandbox): Path = sandbox.repo("remotes") {
        commit("one") { file("a.txt", "1") }
        git("remote", "add", "origin", "https://ann:s3cret@example.invalid/graft.git")
        git("remote", "set-url", "--push", "origin", "ssh://git@example.invalid/graft.git")
        git("remote", "add", "fork", "git@example.invalid:ann/graft.git")
        git("remote", "add", "mirror", "ssh://ann:s3cret@example.invalid/graft.git")
        git("remote", "add", "plain", "https://example.invalid/plain.git")
    }
}
