// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.testing.GitSandbox
import java.nio.file.Files
import java.nio.file.Path

/** The repositories the `status` fixtures are recorded from, one per state the summary must read. */
object StatusRepos {
    fun build(sandbox: GitSandbox): Map<String, Path> = buildMap {
        // main tracks the local branch `base`: main has two commits base hasn't, base one main hasn't.
        put(
            "tracking",
            sandbox.repo("tracking") {
                commit("one") { file("a.txt", "1") }
                branch("base") { commit("base only") { file("b.txt", "b") } }
                commit("two") { file("a.txt", "2") }
                commit("three") { file("a.txt", "3") }
                git("branch", "--set-upstream-to=base", "main")
            },
        )
        put(
            "untracked",
            sandbox.repo("untracked") { commit("one") { file("a.txt", "1") } }.also {
                Files.writeString(it.resolve("new.txt"), "new")
            },
        )
        put(
            "staged",
            sandbox.repo("staged") { commit("one") { file("a.txt", "1") } }.also {
                Files.writeString(it.resolve("a.txt"), "changed")
                sandbox.git(it, "add", "a.txt")
            },
        )
        put(
            "detached",
            sandbox.repo("detached") {
                commit("one") { file("a.txt", "1") }
                commit("two") { file("a.txt", "2") }
                git("switch", "--quiet", "--detach", "HEAD~1")
            },
        )
        put("unborn", sandbox.repo("unborn"))
        put(
            "gone",
            sandbox.repo("gone") {
                commit("one") { file("a.txt", "1") }
                branch("base")
                git("branch", "--set-upstream-to=base", "main")
                git("branch", "--quiet", "-D", "base")
            },
        )
    }
}
