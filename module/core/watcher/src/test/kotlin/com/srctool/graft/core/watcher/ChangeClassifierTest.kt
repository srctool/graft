// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.watcher

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a changed path means for a repository (Architecture, "Refresh"). Pure: no files are touched. */
class ChangeClassifierTest {
    private val root: Path = Paths.get("").toAbsolutePath().root
    private val work = root.resolve("src").resolve("app")
    private val git = work.resolve(".git")

    private val plain = ChangeClassifier(WatchedRepository(work, git, git))

    private fun Path.at(relative: String): Path = relative.split('/').fold(this) { p, part -> p.resolve(part) }

    @Test
    fun `HEAD, refs, packed-refs, reflogs and config are ref changes`() {
        listOf(
            "HEAD", "ORIG_HEAD", "FETCH_HEAD", "refs/heads/main", "refs/remotes/origin/main", "refs/tags/v1",
            "refs/stash", "refs/graft/shelves/x", "packed-refs", "logs/HEAD", "logs/refs/heads/main", "config",
        )
            .forEach { assertEquals(ChangeKind.REFS, plain.classify(git.at(it)), it) }
    }

    @Test
    fun `the index is a status change`() {
        assertEquals(ChangeKind.STATUS, plain.classify(git.at("index")))
    }

    @Test
    fun `merge, rebase, cherry-pick, revert and bisect markers are operation changes`() {
        listOf(
            "MERGE_HEAD",
            "CHERRY_PICK_HEAD",
            "REVERT_HEAD",
            "BISECT_LOG",
            "rebase-merge/done",
            "rebase-apply/0001",
            "sequencer/todo",
        )
            .forEach { assertEquals(ChangeKind.OPERATION, plain.classify(git.at(it)), it) }
    }

    @Test
    fun `objects, lock files and Graft's own files are ignored`() {
        listOf(
            "objects/ab/cdef", "objects/pack/pack-1.pack", "index.lock", "refs/heads/main.lock", "HEAD.lock",
            "packed-refs.lock", "graft/state.json", "description", "hooks/pre-commit", "COMMIT_EDITMSG",
        )
            .forEach { assertNull(plain.classify(git.at(it)), it) }
    }

    @Test
    fun `working-tree files are status changes, and the git folder itself isn't`() {
        assertEquals(ChangeKind.STATUS, plain.classify(work.at("src/Main.kt")))
        assertEquals(ChangeKind.STATUS, plain.classify(work.at("README.md")))
        assertNull(plain.classify(git))
        assertNull(plain.classify(root.resolve("elsewhere").resolve("x")))
    }

    @Test
    fun `a caller's ignore rule hides working-tree paths`() {
        val c = ChangeClassifier(WatchedRepository(work, git, git)) { it.startsWith(work.at("build")) }
        assertNull(c.classify(work.at("build/out.class")))
        assertEquals(ChangeKind.STATUS, c.classify(work.at("src/a.kt")))
    }

    @Test
    fun `a linked worktree sees its own HEAD and index, and the shared refs`() {
        val main = root.resolve("src").resolve("app").resolve(".git")
        val wtGit = main.at("worktrees/feature")
        val wtWork = root.resolve("src").resolve("app.worktrees").resolve("feature")
        val c = ChangeClassifier(WatchedRepository(wtWork, wtGit, main))
        assertEquals(ChangeKind.REFS, c.classify(wtGit.at("HEAD")))
        assertEquals(ChangeKind.STATUS, c.classify(wtGit.at("index")))
        assertEquals(ChangeKind.OPERATION, c.classify(wtGit.at("rebase-merge/done")))
        assertEquals(ChangeKind.REFS, c.classify(main.at("refs/heads/main")))
        assertEquals(ChangeKind.REFS, c.classify(main.at("packed-refs")))
        // The main worktree's HEAD and index, and other worktrees, aren't this worktree's business.
        assertNull(c.classify(main.at("HEAD")))
        assertNull(c.classify(main.at("index")))
        assertNull(c.classify(main.at("worktrees/other/HEAD")))
        assertNull(c.classify(main.at("objects/ab/cd")))
        assertEquals(ChangeKind.STATUS, c.classify(wtWork.at("src/a.kt")))
        assertNull(c.classify(wtWork.at(".git")))
    }

    @Test
    fun `a bare repository has no working tree`() {
        val bare = root.resolve("srv").resolve("app.git")
        val c = ChangeClassifier(WatchedRepository(null, bare, bare))
        assertEquals(ChangeKind.REFS, c.classify(bare.at("refs/heads/main")))
        assertEquals(ChangeKind.REFS, c.classify(bare.at("HEAD")))
        assertNull(c.classify(root.resolve("srv").resolve("other").resolve("x")))
    }

    @Test
    fun `folders the watcher never descends into`() {
        assertEquals(true, plain.skipsFolder(git.at("objects")))
        assertEquals(true, plain.skipsFolder(git.at("worktrees")))
        assertEquals(false, plain.skipsFolder(git.at("refs")))
        assertEquals(false, plain.skipsFolder(work.at("src")))
    }
}
