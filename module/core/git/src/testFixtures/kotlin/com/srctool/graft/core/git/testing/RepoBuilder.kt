// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git.testing

import java.nio.file.Files
import java.nio.file.Path

/**
 * The repository DSL from the Testing Strategy:
 * ```
 * sandbox.repo {
 *     commit("init") { file("a.txt", "1") }
 *     branch("feature") { commit("f1") { file("b.txt", "x") } }
 *     commit("main 2") { file("a.txt", "2") }
 *     merge("feature")
 * }
 * ```
 * Worktrees and submodules join the DSL with the milestones that need them.
 */
public class RepoBuilder(private val sandbox: GitSandbox, public val path: Path) {
    /** Writes the files, stages everything and commits. Empty commits are allowed. */
    public fun commit(message: String, files: WorkingTree.() -> Unit = {}) {
        WorkingTree(path).files()
        git("add", "--all")
        git("commit", "--quiet", "--allow-empty", "-m", message)
    }

    /** Creates [name] at the current commit, runs [block] on it, then switches back. */
    public fun branch(name: String, block: RepoBuilder.() -> Unit = {}) {
        val previous = git("rev-parse", "--abbrev-ref", "HEAD").trim()
        git("switch", "--quiet", "-c", name)
        block()
        git("switch", "--quiet", previous)
    }

    public fun merge(branch: String, message: String = "Merge $branch") {
        git("merge", "--quiet", "--no-ff", "-m", message, branch)
    }

    /** A lightweight tag, or an annotated one when [message] is given. */
    public fun tag(name: String, message: String? = null) {
        if (message == null) git("tag", name) else git("tag", "-a", "-m", message, name)
    }

    public fun git(vararg args: String): String = sandbox.git(path, *args)

    /** Files in the working tree, relative to the repository root. */
    public class WorkingTree internal constructor(private val root: Path) {
        public fun file(relative: String, content: String) {
            file(relative, content.toByteArray())
        }

        public fun file(relative: String, content: ByteArray) {
            val target = root.resolve(relative)
            Files.createDirectories(target.parent)
            Files.write(target, content)
        }

        public fun delete(relative: String) {
            Files.deleteIfExists(root.resolve(relative))
        }
    }
}
