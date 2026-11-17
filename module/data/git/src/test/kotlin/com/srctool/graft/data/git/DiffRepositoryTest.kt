// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.graft.data.git.api.models.DiffLine
import com.srctool.graft.data.git.api.models.DiffOptions
import com.srctool.graft.data.git.api.models.DiffTarget
import com.srctool.graft.data.git.api.models.FileContent
import com.srctool.graft.data.git.api.models.FileStatus
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.di.GitDataModule
import com.srctool.sinew.models.errorOrNull
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Details, diffs and contents read live from the `diffs` repository, on the latest Git and on 2.38 (`git-min`). */
@ExtendWith(GitSandboxExtension::class)
class DiffRepositoryTest {
    private fun GitSandbox.data() = GitDataModule(runner())

    @Test
    fun `commit details match what the fixtures recorded`(sandbox: GitSandbox) = runBlocking<Unit> {
        val built = DiffRepo.build(sandbox)
        val details = sandbox.data().commits.details(built.path, ObjectId(built.change)).getOrThrow()
        assertEquals("Change many things", details.subject)
        assertEquals(listOf(ObjectId(built.base)), details.parents)
        assertEquals(9, details.files.size)
        assertEquals("Co-authored-by", details.trailers.first().key)
        // The same bytes the fixtures were recorded from: the recorded file list is what git still prints.
        assertEquals(DiffParserTestFiles.change(), details.files)
    }

    @Test
    fun `a root commit's details list its files`(sandbox: GitSandbox) = runBlocking<Unit> {
        val built = DiffRepo.build(sandbox)
        val details = sandbox.data().commits.details(built.path, ObjectId(built.base)).getOrThrow()
        assertTrue(details.parents.isEmpty())
        assertTrue(details.files.all { it.status == FileStatus.ADDED })
    }

    @Test
    fun `a commit's diff, and only the paths asked for`(sandbox: GitSandbox) = runBlocking<Unit> {
        val built = DiffRepo.build(sandbox)
        val diffs = sandbox.data().diffs
        val all = diffs.diff(built.path, DiffTarget.Commit(ObjectId(built.change))).getOrThrow()
        assertEquals(9, all.size)
        val one = diffs.diff(
            built.path,
            DiffTarget.Commit(ObjectId(built.change)),
            paths = listOf("a.txt"),
        ).getOrThrow()
        assertEquals(listOf("a.txt"), one.map { it.file.path })
        assertEquals(2, one.single().hunks.size)
    }

    @Test
    fun `between two commits, and since a fork point`(sandbox: GitSandbox) = runBlocking<Unit> {
        val built = DiffRepo.build(sandbox)
        sandbox.git(built.path, "branch", "--quiet", "fork", built.base)
        val diffs = sandbox.data().diffs
        val between = diffs.files(built.path, DiffTarget.Between(built.base, built.latin1)).getOrThrow()
        assertTrue(between.any { it.path == DiffRepo.LATIN1_NAME })
        assertTrue(between.any { it.path == "lib/New.kt" })
        // main since it forked from `fork` (at base) is everything after base.
        val since = diffs.files(built.path, DiffTarget.Since("fork", "main")).getOrThrow()
        assertEquals(between, since)
        val reverse = diffs.files(built.path, DiffTarget.Since("main", "fork")).getOrThrow()
        assertTrue(reverse.isEmpty())
    }

    @Test
    fun `options change context, renames and whitespace`(sandbox: GitSandbox) = runBlocking<Unit> {
        val built = DiffRepo.build(sandbox)
        val diffs = sandbox.data().diffs
        val target = DiffTarget.Commit(ObjectId(built.change))
        val tight = diffs.diff(built.path, target, DiffOptions(contextLines = 0), listOf("a.txt")).getOrThrow().single()
        assertTrue(tight.hunks.flatMap { it.lines }.none { it.kind == DiffLine.Kind.CONTEXT })
        val noRenames = diffs.files(built.path, target, DiffOptions(detectRenames = false)).getOrThrow()
        assertEquals(FileStatus.DELETED, noRenames.single { it.path == "lib/Old.kt" }.status)
        assertEquals(FileStatus.ADDED, noRenames.single { it.path == "lib/New.kt" }.status)
    }

    @Test
    fun `a merge is compared with its first parent`(sandbox: GitSandbox) = runBlocking<Unit> {
        val repo =
            sandbox.repo {
                commit("one") { file("a.txt", "1") }
                branch("side") { commit("side") { file("side.txt", "s") } }
                commit("two") { file("a.txt", "2") }
                merge("side")
            }
        val merge = ObjectId(sandbox.git(repo, "rev-parse", "HEAD").trim())
        val details = sandbox.data().commits.details(repo, merge).getOrThrow()
        assertEquals(2, details.parents.size)
        assertEquals(listOf("side.txt"), details.files.map { it.path })
    }

    @Test
    fun `file contents are text, binary, missing or too large, and messages whole`(sandbox: GitSandbox) =
        runBlocking<Unit> {
            val built = DiffRepo.build(sandbox)
            val latin1File = sandbox.data().diffs.files(
                built.path,
                DiffTarget.Commit(ObjectId(built.latin1)),
            ).getOrThrow().single()
            sandbox.data().diffs.contents(built.path).use { contents ->
                val text = assertIs<FileContent.Text>(contents.read(built.change, "noeol.txt").getOrThrow())
                assertEquals("end", text.text)
                assertEquals(FileContent.Binary(6), contents.read(built.change, "pic.bin").getOrThrow())
                assertEquals(FileContent.Missing, contents.read(built.base, "added.txt").getOrThrow())
                assertEquals(FileContent.Missing, contents.read(built.change, "lib").getOrThrow())
                assertIs<FileContent.TooLarge>(contents.read(built.base, "big.txt", maxBytes = 1_000).getOrThrow())
                // A Latin-1 path can't be named as `revision:path` (the request is UTF-8), so it reads as missing...
                assertEquals(FileContent.Missing, contents.read(built.latin1, DiffRepo.LATIN1_NAME).getOrThrow())
                // ...and by its object id, from the file list, it reads fine.
                val latin1 = assertIs<FileContent.Text>(contents.read(latin1File.newObject!!).getOrThrow())
                assertEquals(DiffRepo.LATIN1_TEXT + "\n", latin1.text)
                // The image diff reads a binary's bytes by object id; over the limit, or not a blob, it gets none.
                val pic = ObjectId(sandbox.git(built.path, "rev-parse", "${built.change}:pic.bin").trim())
                val bytes = contents.readBytes(pic).getOrThrow()
                assertEquals(6, bytes?.size)
                assertTrue(bytes!!.contains(0.toByte()))
                assertEquals(null, contents.readBytes(pic, maxBytes = 2).getOrThrow())
                assertEquals(null, contents.readBytes(ObjectId(built.change)).getOrThrow())
                val message = contents.message(ObjectId(built.change)).getOrThrow()
                assertTrue(message.startsWith("Change many things\n\nThe body explains why."), message)
            }
        }

    @Test
    fun `outside a repository the diff fails as not found`(sandbox: GitSandbox) = runBlocking<Unit> {
        val error =
            sandbox.data().diffs.files(sandbox.dir("plain"), DiffTarget.Between("HEAD~1", "HEAD")).errorOrNull
        assertEquals("GIT-R-FILS-NF", error?.code)
        // `git diff` would have compared two files named like the revisions; diff-tree refuses.
        val since = sandbox.data().diffs.files(sandbox.dir("plain"), DiffTarget.Since("a", "b")).errorOrNull
        assertEquals("GIT-R-MBAS-NF", since?.code)
    }
}

/** The recorded file list for `change`, shared with the integration test to show live git agrees with the fixture. */
private object DiffParserTestFiles {
    fun change() = com.srctool.graft.data.git.implementation.source.local.ChangedFilesParser.parse(
        Fixtures.read("files", "change"),
    )
}
