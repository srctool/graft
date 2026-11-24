// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.GitIgnoreRefusedException
import com.srctool.graft.core.git.IgnoreProblem
import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.graft.data.git.api.models.CleanScope
import com.srctool.graft.data.git.api.models.IgnorePatterns
import com.srctool.graft.data.git.api.models.IgnoreTarget
import com.srctool.graft.data.git.api.models.IgnoreWarning
import com.srctool.graft.data.git.implementation.repositories.GitCleanRepository
import com.srctool.graft.data.git.implementation.repositories.GitIgnoreRepository
import com.srctool.graft.data.git.implementation.repositories.GitLocalHistoryRepository
import com.srctool.graft.data.git.implementation.repositories.MutationRecorder
import com.srctool.graft.data.git.implementation.source.local.IgnoreText
import com.srctool.graft.data.git.implementation.source.local.OpLogStore
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.errorOrNull
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ignore and Clean on real repositories: every target and pattern kind, negations, tracked files, the global excludes
 * file, clean previews with ignored and nested repositories, the snapshot before a clean, and awkward paths.
 */
@ExtendWith(GitSandboxExtension::class)
class IgnoreCleanTest {
    private class Rig(val sandbox: GitSandbox) {
        val runner = sandbox.runner()
        val recorder = MutationRecorder(runner, OpLogStore())
        val history = GitLocalHistoryRepository(runner)
        val globalDefault: Path = sandbox.dir("xdg-config").resolve("git").resolve("ignore")
        val ignore = GitIgnoreRepository(runner, recorder, globalDefault)
        val clean = GitCleanRepository(runner, history, recorder)
    }

    private fun repo(sandbox: GitSandbox, build: (Path) -> Unit = {}): Path =
        sandbox.repo { commit("one") { file("tracked.txt", "t\n") } }.also(build)

    private fun Path.write(relative: String, content: String = "x\n"): Path {
        val file = resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        return file
    }

    private fun status(sandbox: GitSandbox, repo: Path): List<String> =
        sandbox.git(repo, "status", "--porcelain", "-uall").lines().filter { it.isNotBlank() }

    // ---- patterns

    @Test
    fun `the menu offers file, extension and folder patterns`() {
        assertEquals("/logs/app.log", IgnorePatterns.file("logs/app.log"))
        assertEquals("*.log", IgnorePatterns.extension("logs/app.log"))
        assertEquals("/build/", IgnorePatterns.folder("build"))
        assertEquals("/build/", IgnorePatterns.folder("build/"))
        assertFailsWith<IllegalArgumentException> { IgnorePatterns.folder("") }
        assertFailsWith<IllegalArgumentException> { IgnorePatterns.folder("/") }
        assertNull(IgnorePatterns.extension("Makefile"))
        assertNull(IgnorePatterns.extension(".env"))
        assertNull(IgnorePatterns.extension("name."))
    }

    @Test
    fun `patterns are relative to the gitignore they go into`() {
        assertEquals("/app.log", IgnorePatterns.file("logs/app.log", base = "logs"))
        assertEquals("/out/", IgnorePatterns.folder("logs/out", base = "logs"))
        assertFailsWith<IllegalArgumentException> { IgnorePatterns.file("other/app.log", base = "logs") }
    }

    @Test
    fun `characters git reads as syntax are escaped so the file ignores that file only`() {
        assertEquals("/a\\*b\\?.txt", IgnorePatterns.file("a*b?.txt"))
        assertEquals("/dir/\\[x\\]", IgnorePatterns.file("dir/[x]"))
        assertEquals("/trail\\ ", IgnorePatterns.file("trail "))
    }

    @Test
    fun `an escaped pattern really ignores only its file`(sandbox: GitSandbox) = runBlocking<Unit> {
        assumeTrue(GitSandbox.isPosix, "Windows can't name a file with * or ?")
        val rig = Rig(sandbox)
        val repo = repo(sandbox) {
            it.write("a*b.txt")
            it.write("axb.txt")
        }
        rig.ignore.add(repo, IgnoreTarget.Gitignore(), listOf(IgnorePatterns.file("a*b.txt"))).getOrThrow()
        assertEquals(listOf("?? .gitignore", "?? axb.txt"), status(sandbox, repo))
    }

    // ---- merging text

    @Test
    fun `merge leaves out lines that are there, keeps order, collapses blank runs`() {
        val merged = IgnoreText.merge(listOf("build/", "*.log", ""), listOf("*.log", "", "", "out/", "tmp/"))
        assertEquals(listOf("out/", "tmp/"), merged.added)
        assertEquals(listOf("*.log"), merged.skipped)
        assertEquals("out/\ntmp/\n", merged.text)
    }

    @Test
    fun `a comment alone is not a change, and a new comment block gets a blank line before it`() {
        assertEquals("", IgnoreText.merge(listOf("a"), listOf("# Logs", "a")).text)
        assertEquals("\n# Logs\nlogs/\n", IgnoreText.merge(listOf("a"), listOf("# Logs", "logs/")).text)
        assertEquals("# Logs\nlogs/\n", IgnoreText.merge(listOf("a", ""), listOf("# Logs", "logs/")).text)
    }

    // ---- targets

    @Test
    fun `adding to the root gitignore creates it and git honours it`(sandbox: GitSandbox) = runBlocking<Unit> {
        val rig = Rig(sandbox)
        val repo = repo(sandbox) { it.write("a.log") }
        val written = rig.ignore.add(repo, IgnoreTarget.Gitignore(), listOf("*.log")).getOrThrow()
        assertTrue(written.created)
        assertEquals(listOf("*.log"), written.added)
        assertEquals("*.log\n", Files.readString(repo.resolve(".gitignore")))
        assertEquals(listOf("?? .gitignore"), status(sandbox, repo))
    }

    @Test
    fun `adding again skips what is there and writes nothing`(sandbox: GitSandbox) = runBlocking<Unit> {
        val rig = Rig(sandbox)
        val repo = repo(sandbox)
        rig.ignore.add(repo, IgnoreTarget.Gitignore(), listOf("*.log", "build/")).getOrThrow()
        val before = Files.readAllBytes(repo.resolve(".gitignore"))
        val again = rig.ignore.add(repo, IgnoreTarget.Gitignore(), listOf("build/", "*.log")).getOrThrow()
        assertEquals(emptyList(), again.added)
        assertEquals(listOf("build/", "*.log"), again.skipped)
        assertFalse(again.created)
        assertTrue(before.contentEquals(Files.readAllBytes(repo.resolve(".gitignore"))))
    }

    @Test
    fun `existing text stays byte for byte, a missing final newline is added, CRLF is followed`(sandbox: GitSandbox) =
        runBlocking<Unit> {
            val rig = Rig(sandbox)
            val repo = repo(sandbox)
            val latin =
                byteArrayOf(
                    '#'.code.toByte(),
                    ' '.code.toByte(),
                    0xE9.toByte(),
                    '\r'.code.toByte(),
                    '\n'.code.toByte(),
                    'a'.code.toByte(),
                )
            Files.write(repo.resolve(".gitignore"), latin)
            rig.ignore.add(repo, IgnoreTarget.Gitignore(), listOf("b")).getOrThrow()
            val after = Files.readAllBytes(repo.resolve(".gitignore"))
            assertTrue(after.copyOf(latin.size).contentEquals(latin))
            assertEquals("\r\nb\r\n", String(after, latin.size, after.size - latin.size, Charsets.UTF_8))
        }

    @Test
    fun `a gitignore in a folder holds patterns relative to it`(sandbox: GitSandbox) = runBlocking<Unit> {
        val rig = Rig(sandbox)
        val repo = repo(sandbox) {
            it.write("logs/app.log")
            it.write("app.log")
        }
        val written =
            rig.ignore.add(repo, IgnoreTarget.Gitignore("logs"), listOf(IgnorePatterns.file("logs/app.log", "logs")))
                .getOrThrow()
        assertEquals(repo.resolve("logs/.gitignore"), written.file)
        assertEquals(listOf("?? app.log", "?? logs/.gitignore"), status(sandbox, repo))
    }

    @Test
    fun `a folder outside the working tree or inside git's own is refused`(sandbox: GitSandbox) = runBlocking<Unit> {
        val rig = Rig(sandbox)
        val repo = repo(sandbox)
        for (bad in listOf("..", "../x", "/etc", ".git", ".git/hooks", "a/../..")) {
            val error = rig.ignore.add(repo, IgnoreTarget.Gitignore(bad), listOf("x")).errorOrNull
            val refused = assertIs<GitIgnoreRefusedException>(error, bad)
            assertEquals(IgnoreProblem.UNSAFE_INPUT, refused.problem)
        }
    }

    @Test
    fun `a pattern with a line break is refused, not split in two`(sandbox: GitSandbox) = runBlocking<Unit> {
        val rig = Rig(sandbox)
        val repo = repo(sandbox)
        val error = rig.ignore.add(repo, IgnoreTarget.Gitignore(), listOf("ok", "bad\n!ok")).errorOrNull
        assertIs<GitIgnoreRefusedException>(error)
        assertFalse(Files.exists(repo.resolve(".gitignore")))
    }

    @Test
    fun `info exclude is this clone only and git honours it`(sandbox: GitSandbox) = runBlocking<Unit> {
        val rig = Rig(sandbox)
        val repo = repo(sandbox) { it.write("scratch.txt") }
        Files.deleteIfExists(repo.resolve(".git/info/exclude"))
        Files.deleteIfExists(repo.resolve(".git/info"))
        val written = rig.ignore.add(repo, IgnoreTarget.InfoExclude, listOf("scratch.txt")).getOrThrow()
        assertEquals(repo.resolve(".git/info/exclude").toRealPath(), written.file.toRealPath())
        assertEquals(emptyList(), status(sandbox, repo))
    }

    @Test
    fun `the global excludes file that does not exist is created at git's default place`(sandbox: GitSandbox) =
        runBlocking<Unit> {
            val rig = Rig(sandbox)
            val repo = repo(sandbox)
            assertFalse(Files.exists(rig.globalDefault))
            val written = rig.ignore.add(repo, IgnoreTarget.GlobalExcludes, listOf(".DS_Store")).getOrThrow()
            assertEquals(rig.globalDefault, written.file)
            assertTrue(written.created)
            assertEquals(".DS_Store\n", Files.readString(rig.globalDefault))
        }

    @Test
    fun `core excludesFile is used when set, with the tilde expanded, and created if missing`(sandbox: GitSandbox) =
        runBlocking<Unit> {
            val rig = Rig(sandbox)
            val repo = repo(sandbox) { it.write(".DS_Store") }
            sandbox.git(repo, "config", "--global", "core.excludesFile", "~/my-ignores/global")
            val written = rig.ignore.add(repo, IgnoreTarget.GlobalExcludes, listOf(".DS_Store")).getOrThrow()
            assertEquals(sandbox.home.resolve("my-ignores/global"), written.file)
            assertTrue(written.created)
            assertFalse(Files.exists(rig.globalDefault))
            assertEquals(emptyList(), status(sandbox, repo))
        }

    @Test
    fun `the global excludes write never edits the git config`(sandbox: GitSandbox) = runBlocking<Unit> {
        val rig = Rig(sandbox)
        val repo = repo(sandbox)
        val before = Files.readAllBytes(sandbox.globalConfig)
        rig.ignore.add(repo, IgnoreTarget.GlobalExcludes, listOf("x")).getOrThrow()
        assertTrue(before.contentEquals(Files.readAllBytes(sandbox.globalConfig)))
    }

    // ---- preview

    @Test
    fun `the preview lists what a pattern matches, untracked and tracked apart`(sandbox: GitSandbox) =
        runBlocking<Unit> {
            val rig = Rig(sandbox)
            val repo = repo(sandbox) {
                it.write("a.log")
                it.write("sub/b.log")
                it.write("keep.txt")
            }
            val preview = rig.ignore.preview(repo, listOf("*.log")).getOrThrow()
            assertEquals(listOf("a.log", "sub/b.log"), preview.untracked)
            assertEquals(emptyList(), preview.tracked)
        }

    @Test
    fun `a tracked file that matches is reported, because ignoring it does nothing`(sandbox: GitSandbox) =
        runBlocking<Unit> {
            val rig = Rig(sandbox)
            val repo = sandbox.repo { commit("one") { file("secret.env", "k=v\n") } }
            val preview = rig.ignore.preview(repo, listOf("*.env")).getOrThrow()
            assertEquals(listOf("secret.env"), preview.tracked)
            assertEquals(emptyList(), preview.untracked)
        }

    @Test
    fun `the preview uses only the typed patterns, not the ones already in the repository`(sandbox: GitSandbox) =
        runBlocking<Unit> {
            val rig = Rig(sandbox)
            val repo = repo(sandbox) {
                it.write(".gitignore", "*.log\n")
                it.write("a.log")
                it.write("b.tmp")
            }
            assertEquals(listOf("b.tmp"), rig.ignore.preview(repo, listOf("*.tmp")).getOrThrow().untracked)
        }

    @Test
    fun `a pattern for a nested gitignore is previewed where that file would apply it`(sandbox: GitSandbox) =
        runBlocking<Unit> {
            val rig = Rig(sandbox)
            val repo = repo(sandbox) {
                it.write("logs/app.log")
                it.write("app.log")
                it.write("logs/deep/x.log")
            }
            assertEquals(
                listOf("logs/app.log"),
                rig.ignore.preview(repo, listOf("/app.log"), base = "logs").getOrThrow().untracked,
            )
            assertEquals(
                listOf("logs/app.log", "logs/deep/x.log"),
                rig.ignore.preview(repo, listOf("*.log"), base = "logs").getOrThrow().untracked,
            )
        }

    @Test
    fun `a negation after an ignored folder is warned about`(sandbox: GitSandbox) = runBlocking<Unit> {
        val rig = Rig(sandbox)
        val repo = repo(sandbox) { it.write("build/keep.o") }
        val preview = rig.ignore.preview(repo, listOf("build/", "!build/keep.o")).getOrThrow()
        assertEquals(listOf(IgnoreWarning.ParentIgnored("!build/keep.o", "build")), preview.warnings)
        // Git agrees with the warning: the file is still ignored.
        assertEquals(listOf("build/keep.o"), preview.untracked)
    }

    @Test
    fun `a negation whose folder is ignored by a rule already in the repository is warned about`(sandbox: GitSandbox) =
        runBlocking<Unit> {
            val rig = Rig(sandbox)
            val repo = repo(sandbox) { it.write(".gitignore", "out/\n") }
            val warnings = rig.ignore.preview(repo, listOf("!out/keep.txt")).getOrThrow().warnings
            assertEquals(listOf(IgnoreWarning.ParentIgnored("!out/keep.txt", "out")), warnings)
        }

    @Test
    fun `negations that can work are not warned about`(sandbox: GitSandbox) = runBlocking<Unit> {
        val rig = Rig(sandbox)
        val repo = repo(sandbox) {
            it.write("a.log")
            it.write("keep.log")
            it.write("docs/x.md")
        }
        assertEquals(emptyList(), rig.ignore.preview(repo, listOf("*.log", "!keep.log")).getOrThrow().warnings)
        assertEquals(
            listOf("a.log"),
            rig.ignore.preview(repo, listOf("*.log", "!keep.log")).getOrThrow().untracked,
        )
        // A folder pattern that does not cover the negated file's folder.
        assertEquals(emptyList(), rig.ignore.preview(repo, listOf("*.md", "!docs/x.md")).getOrThrow().warnings)
    }

    @Test
    fun `comments and blank lines in a preview match nothing`(sandbox: GitSandbox) = runBlocking<Unit> {
        val rig = Rig(sandbox)
        val repo = repo(sandbox) { it.write("a.log") }
        val preview = rig.ignore.preview(repo, listOf("# *.log", "", "   ")).getOrThrow()
        assertEquals(emptyList(), preview.untracked)
    }

    @Test
    fun `a preview leaves no file behind in the git folder`(sandbox: GitSandbox) = runBlocking<Unit> {
        val rig = Rig(sandbox)
        val repo = repo(sandbox) { it.write("a.log") }
        rig.ignore.preview(repo, listOf("*.log")).getOrThrow()
        val leftovers = Files.list(repo.resolve(".git/graft")).use { list ->
            list.map { it.fileName.toString() }.toList()
        }
        assertEquals(emptyList(), leftovers.filter { it.startsWith("ignore-preview") })
    }

    // ---- why is this ignored

    @Test
    fun `explain names the file, line and pattern`(sandbox: GitSandbox) = runBlocking<Unit> {
        val rig = Rig(sandbox)
        val repo = repo(sandbox) {
            it.write(".gitignore", "# logs\n*.log\nbuild/\n")
            it.write("build/c.o")
            it.write("a.log")
            it.write("fine.txt")
        }
        val why = rig.ignore.explain(repo, listOf("a.log", "build/c.o", "fine.txt")).getOrThrow()
        assertEquals(listOf("a.log", "build/c.o"), why.map { it.path })
        assertEquals(".gitignore", why[0].source)
        assertEquals(2, why[0].line)
        assertEquals("*.log", why[0].pattern)
        assertEquals(3, why[1].line)
        assertEquals("build/", why[1].pattern)
    }

    @Test
    fun `explain also answers for info exclude and a tracked file, and says nothing for the rest`(sandbox: GitSandbox) =
        runBlocking<Unit> {
            val rig = Rig(sandbox)
            val repo = repo(sandbox) {
                it.write(".git/info/exclude", "tracked.txt\nzzz\n")
            }
            val why = rig.ignore.explain(repo, listOf("tracked.txt", "other.txt")).getOrThrow()
            assertEquals(1, why.size)
            assertEquals("tracked.txt", why.single().path)
            assertTrue(why.single().source.endsWith("info/exclude"))
            assertEquals(emptyList(), rig.ignore.explain(repo, emptyList()).getOrThrow())
        }

    @Test
    fun `explain reports a negation as not ignoring`(sandbox: GitSandbox) = runBlocking<Unit> {
        val rig = Rig(sandbox)
        val repo = repo(sandbox) { it.write(".gitignore", "*.log\n!keep.log\n") }
        val why = rig.ignore.explain(repo, listOf("keep.log")).getOrThrow().single()
        assertTrue(why.negated)
        assertEquals(2, why.line)
    }

    @Test
    fun `explain handles paths with spaces, quotes, dashes and non-ASCII names`(sandbox: GitSandbox) =
        runBlocking<Unit> {
            val rig = Rig(sandbox)
            val repo = repo(sandbox) { it.write(".gitignore", "*.log\n") }
            val names = listOf("sp ace/a.log", "-dash.log", "é/ü.log")
            val why = rig.ignore.explain(repo, names).getOrThrow()
            assertEquals(names.sorted(), why.map { it.path }.sorted())
        }

    // ---- already tracked

    @Test
    fun `untracking keeps the file and a pattern then hides it`(sandbox: GitSandbox) = runBlocking<Unit> {
        val rig = Rig(sandbox)
        val repo = sandbox.repo {
            commit("one") {
                file("config.local", "k\n")
                file("sp ace/-dash.txt", "d\n")
            }
        }
        rig.ignore.untrack(repo, listOf("config.local", "sp ace/-dash.txt")).getOrThrow()
        assertTrue(Files.exists(repo.resolve("config.local")))
        assertTrue(Files.exists(repo.resolve("sp ace/-dash.txt")))
        assertEquals(
            listOf("D  config.local", "D  \"sp ace/-dash.txt\""),
            status(sandbox, repo).filter {
                it.startsWith("D")
            },
        )
        rig.ignore.add(repo, IgnoreTarget.Gitignore(), listOf("config.local", "sp ace/")).getOrThrow()
        assertEquals(listOf("?? .gitignore"), status(sandbox, repo).filter { it.startsWith("?") })
    }

    @Test
    fun `untracking a path git does not track fails and changes nothing`(sandbox: GitSandbox) = runBlocking<Unit> {
        val rig = Rig(sandbox)
        val repo = repo(sandbox) { it.write("loose.txt") }
        assertIs<Result.Failure>(rig.ignore.untrack(repo, listOf("tracked.txt", "loose.txt")))
        assertEquals(listOf("?? loose.txt"), status(sandbox, repo))
        assertEquals(Unit, rig.ignore.untrack(repo, emptyList()).getOrThrow())
    }

    @Test
    fun `a file named like a pattern is untracked alone`(sandbox: GitSandbox) = runBlocking<Unit> {
        assumeTrue(GitSandbox.isPosix, "Windows can't name a file with *")
        val rig = Rig(sandbox)
        val repo = sandbox.repo {
            commit("one") {
                file("*.txt", "star\n")
                file("a.txt", "a\n")
            }
        }
        rig.ignore.untrack(repo, listOf("*.txt")).getOrThrow()
        assertEquals("a.txt", sandbox.git(repo, "ls-files").trim())
        assertTrue(Files.exists(repo.resolve("*.txt")))
    }

    // ---- templates

    @Test
    fun `the bundled templates are there with their licence`(sandbox: GitSandbox) {
        val ignore = Rig(sandbox).ignore
        val names = ignore.templates().map { it.name }
        for (expected in listOf("Kotlin", "Gradle", "Node", "Python", "macOS", "Windows", "JetBrains")) {
            assertTrue(expected in names, expected)
        }
        assertEquals(names.sortedBy { it.lowercase() }, names, "the index is sorted")
        assertTrue(ignore.templates().all { t -> t.lines.any { it.isNotBlank() } })
        assertTrue(ignore.templatesLicense().startsWith("CC0 1.0 Universal"))
    }

    @Test
    fun `two templates merge without repeating a line`(sandbox: GitSandbox) = runBlocking<Unit> {
        val rig = Rig(sandbox)
        val repo = repo(sandbox)
        fun template(name: String) = rig.ignore.templates().first { it.name == name }.lines
        rig.ignore.add(repo, IgnoreTarget.Gitignore(), template("Kotlin")).getOrThrow()
        rig.ignore.add(repo, IgnoreTarget.Gitignore(), template("Gradle")).getOrThrow()
        val lines = Files.readAllLines(repo.resolve(".gitignore")).filter { it.isNotBlank() }
        assertEquals(lines.size, lines.toSet().size, "duplicates in $lines")
        val again = rig.ignore.add(repo, IgnoreTarget.Gitignore(), template("Gradle")).getOrThrow()
        assertEquals(emptyList(), again.added)
    }

    // ---- clean: preview

    @Test
    fun `the preview lists untracked files and folders, largest first, with sizes`(sandbox: GitSandbox) =
        runBlocking<Unit> {
            val rig = Rig(sandbox)
            val repo = repo(sandbox) {
                it.write("small.txt", "1234")
                it.write("big/data.bin", "x".repeat(5000))
                it.write("big/more.bin", "x".repeat(1000))
                it.write("mid.txt", "x".repeat(300))
            }
            val preview = rig.clean.preview(repo).getOrThrow()
            assertEquals(listOf("big/", "mid.txt", "small.txt"), preview.entries.map { it.path })
            assertEquals(listOf(6000L, 300L, 4L), preview.entries.map { it.bytes })
            assertEquals(listOf(true, false, false), preview.entries.map { it.directory })
            assertEquals(6304L, preview.totalBytes)
            assertEquals(0L, preview.lostBytes)
        }

    @Test
    fun `ignored files are listed only for the scopes that include them, and marked as not restorable`(
        sandbox: GitSandbox,
    ) = runBlocking<Unit> {
        val rig = Rig(sandbox)
        val repo = repo(sandbox) {
            it.write(".gitignore", "*.log\nbuild/\n")
            it.write("a.log", "x".repeat(10))
            it.write("build/c.o", "x".repeat(100))
            it.write("new.txt", "x".repeat(1))
        }
        sandbox.git(repo, "add", ".gitignore")
        sandbox.git(repo, "commit", "-qm", "ignore")
        assertEquals(
            listOf("new.txt"),
            rig.clean.preview(repo, CleanScope.UNTRACKED).getOrThrow().entries.map {
                it.path
            },
        )
        val both = rig.clean.preview(repo, CleanScope.UNTRACKED_AND_IGNORED).getOrThrow()
        assertEquals(listOf("build/", "a.log", "new.txt"), both.entries.map { it.path })
        assertEquals(110L, both.lostBytes)
        val only = rig.clean.preview(repo, CleanScope.IGNORED_ONLY).getOrThrow()
        assertEquals(listOf("build/", "a.log"), only.entries.map { it.path })
        assertEquals(110L, only.lostBytes)
    }

    @Test
    fun `files over the snapshot size limit count as lost too`(sandbox: GitSandbox) = runBlocking<Unit> {
        val rig = Rig(sandbox)
        val repo = repo(sandbox) { it.write("huge.bin", "x".repeat(20)) }
        val tiny =
            GitCleanRepository(
                rig.runner,
                rig.history,
                rig.recorder,
                com.srctool.graft.data.git.api.models.LocalHistoryLimits(maxFileBytes = 10),
            )
        assertEquals(20L, tiny.preview(repo).getOrThrow().lostBytes)
        assertEquals(0L, rig.clean.preview(repo).getOrThrow().lostBytes)
    }

    @Test
    fun `a nested repository is flagged and not mixed in with ordinary entries`(sandbox: GitSandbox) =
        runBlocking<Unit> {
            val rig = Rig(sandbox)
            val repo = repo(sandbox) {
                it.write("plain/f.txt")
                sandbox.git(
                    it,
                    "init",
                    "--quiet",
                    "--initial-branch=main",
                    it.resolve("vendor/lib").also(Files::createDirectories).toString(),
                )
                it.write("vendor/lib/code.txt", "x".repeat(50))
            }
            val preview = rig.clean.preview(repo).getOrThrow()
            val nested = preview.entries.filter { it.nestedRepository }
            assertEquals(listOf("vendor/"), nested.map { it.path })
            assertEquals(listOf("plain/"), preview.entries.filter { !it.nestedRepository }.map { it.path })
            assertEquals(nested.single().bytes, nested.single().lostBytes)
        }

    @Test
    fun `a submodule is never in the preview and is never cleaned`(sandbox: GitSandbox) = runBlocking<Unit> {
        val rig = Rig(sandbox)
        val source = sandbox.repo("source") { commit("s") { file("lib.txt", "lib\n") } }
        val repo = repo(sandbox) { it.write("loose.txt") }
        sandbox.git(repo, "-c", "protocol.file.allow=always", "submodule", "add", "-q", source.toString(), "mod")
        sandbox.git(repo, "commit", "-qm", "add submodule")
        repo.write("mod/untracked-in-submodule.txt")
        val preview = rig.clean.preview(repo, CleanScope.UNTRACKED_AND_IGNORED).getOrThrow()
        assertEquals(listOf("loose.txt"), preview.entries.map { it.path })
        assertIs<Result.Failure>(rig.clean.clean(repo, CleanScope.UNTRACKED, listOf("mod/")))
        assertTrue(Files.exists(repo.resolve("mod/lib.txt")))
        assertTrue(Files.exists(repo.resolve("mod/untracked-in-submodule.txt")))
    }

    // ---- clean: running it

    @Test
    fun `clean removes the selected paths only, after a snapshot that holds them`(sandbox: GitSandbox) =
        runBlocking<Unit> {
            val rig = Rig(sandbox)
            val repo = repo(sandbox) {
                it.write("a.txt", "keep me\n")
                it.write("b.txt", "remove me\n")
                it.write("dir/c.txt", "remove me too\n")
            }
            val result = rig.clean.clean(repo, CleanScope.UNTRACKED, listOf("b.txt", "dir/")).getOrThrow()
            assertEquals(listOf("b.txt", "dir/"), result.removed)
            assertEquals(0L, result.lostBytes)
            assertTrue(Files.exists(repo.resolve("a.txt")))
            assertFalse(Files.exists(repo.resolve("b.txt")))
            assertFalse(Files.exists(repo.resolve("dir")))
            val snapshot = assertNotNull(result.snapshot)
            assertEquals("before clean", snapshot.label)
            // The way back works: the removed files are in the snapshot.
            rig.history.restoreFile(repo, snapshot.id, "b.txt").getOrThrow()
            rig.history.restoreFile(repo, snapshot.id, "dir/c.txt").getOrThrow()
            assertEquals("remove me\n", Files.readString(repo.resolve("b.txt")))
            assertEquals("remove me too\n", Files.readString(repo.resolve("dir/c.txt")))
        }

    @Test
    fun `naming a folder without its slash works`(sandbox: GitSandbox) = runBlocking<Unit> {
        val rig = Rig(sandbox)
        val repo = repo(sandbox) { it.write("dir/c.txt") }
        rig.clean.clean(repo, CleanScope.UNTRACKED, listOf("dir")).getOrThrow()
        assertFalse(Files.exists(repo.resolve("dir")))
    }

    @Test
    fun `ignored files are removed only when the scope says so, and the result counts what cannot come back`(
        sandbox: GitSandbox,
    ) = runBlocking<Unit> {
        val rig = Rig(sandbox)
        val repo = repo(sandbox) {
            it.write(".gitignore", "*.log\n")
            it.write("a.log", "x".repeat(40))
            it.write("n.txt", "n")
        }
        sandbox.git(repo, "add", ".gitignore")
        sandbox.git(repo, "commit", "-qm", "ignore")
        assertIs<Result.Failure>(rig.clean.clean(repo, CleanScope.UNTRACKED, listOf("a.log")))
        assertTrue(Files.exists(repo.resolve("a.log")))
        val result = rig.clean.clean(repo, CleanScope.UNTRACKED_AND_IGNORED, listOf("a.log", "n.txt")).getOrThrow()
        assertEquals(40L, result.lostBytes)
        assertFalse(Files.exists(repo.resolve("a.log")))
        assertFalse(Files.exists(repo.resolve("n.txt")))
        // The snapshot has the untracked file, not the ignored one.
        rig.history.restoreFile(repo, assertNotNull(result.snapshot).id, "n.txt").getOrThrow()
        assertIs<Result.Failure>(rig.history.restoreFile(repo, result.snapshot!!.id, "a.log"))
    }

    @Test
    fun `a selection that is no longer in the preview removes nothing at all`(sandbox: GitSandbox) = runBlocking<Unit> {
        val rig = Rig(sandbox)
        val repo = repo(sandbox) { it.write("a.txt") }
        val before = rig.history.snapshots(repo).getOrThrow().size
        val error = rig.clean.clean(
            repo,
            CleanScope.UNTRACKED,
            listOf("a.txt", "tracked.txt", "../outside"),
        ).errorOrNull
        val refused = assertIs<GitIgnoreRefusedException>(error)
        assertEquals(IgnoreProblem.NOT_IN_PREVIEW, refused.problem)
        assertEquals("tracked.txt", refused.detail)
        assertTrue(Files.exists(repo.resolve("a.txt")))
        assertTrue(Files.exists(repo.resolve("tracked.txt")))
        assertEquals(before, rig.history.snapshots(repo).getOrThrow().size, "no snapshot for a refused clean")
    }

    @Test
    fun `a nested repository needs its own confirmation`(sandbox: GitSandbox) = runBlocking<Unit> {
        val rig = Rig(sandbox)
        val repo = repo(sandbox) {
            it.write("plain.txt")
            sandbox.git(
                it,
                "init",
                "--quiet",
                "--initial-branch=main",
                it.resolve("vendor/lib").also(Files::createDirectories).toString(),
            )
            it.write("vendor/lib/code.txt")
        }
        val refused = rig.clean.clean(repo, CleanScope.UNTRACKED, listOf("plain.txt", "vendor/")).errorOrNull
        assertEquals(IgnoreProblem.NESTED_REPOSITORY, assertIs<GitIgnoreRefusedException>(refused).problem)
        assertTrue(Files.exists(repo.resolve("plain.txt")), "nothing is removed when one path is refused")
        assertTrue(Files.exists(repo.resolve("vendor/lib/.git")))

        val done =
            rig.clean.clean(
                repo,
                CleanScope.UNTRACKED,
                listOf("plain.txt", "vendor/"),
                setOf("vendor/"),
            ).getOrThrow()
        assertEquals(listOf("plain.txt", "vendor/"), done.removed)
        assertFalse(Files.exists(repo.resolve("vendor/lib")))
    }

    @Test
    fun `an empty selection does nothing and takes no snapshot`(sandbox: GitSandbox) = runBlocking<Unit> {
        val rig = Rig(sandbox)
        val repo = repo(sandbox) { it.write("a.txt") }
        val result = rig.clean.clean(repo, CleanScope.UNTRACKED, emptyList()).getOrThrow()
        assertEquals(emptyList(), result.removed)
        assertNull(result.snapshot)
        assertTrue(Files.exists(repo.resolve("a.txt")))
        assertEquals(0, rig.history.snapshots(repo).getOrThrow().size)
    }

    @Test
    fun `paths with spaces, leading dashes and non-ASCII names are cleaned exactly`(sandbox: GitSandbox) =
        runBlocking<Unit> {
            val rig = Rig(sandbox)
            val names = listOf("-rf", "sp ace.txt", "-dir/-inner.txt", "é ü.txt")
            val repo = repo(sandbox) {
                names.forEach { name -> it.write(name) }
                it.write("survivor.txt")
            }
            val preview = rig.clean.preview(repo).getOrThrow().entries.map { it.path }
            assertEquals(listOf("-dir/", "-rf", "sp ace.txt", "survivor.txt", "é ü.txt"), preview.sorted())
            rig.clean.clean(repo, CleanScope.UNTRACKED, listOf("-dir/", "-rf", "sp ace.txt", "é ü.txt")).getOrThrow()
            assertEquals(
                listOf("survivor.txt", "tracked.txt"),
                Files.list(repo).use { list ->
                    list.map { it.fileName.toString() }.filter {
                        it !=
                            ".git"
                    }.sorted().toList()
                },
            )
        }

    @Test
    fun `names with quotes and tabs, and names that look like patterns, are exact`(sandbox: GitSandbox) =
        runBlocking<Unit> {
            assumeTrue(GitSandbox.isPosix, "Windows can't name a file with these characters")
            val rig = Rig(sandbox)
            val repo = repo(sandbox) {
                it.write("*.txt")
                it.write("a.txt")
                it.write("we\"ird.txt")
                it.write("tab\there.txt")
            }
            val preview = rig.clean.preview(repo).getOrThrow().entries.map { it.path }
            assertTrue("we\"ird.txt" in preview && "tab\there.txt" in preview && "*.txt" in preview, preview.toString())
            rig.clean.clean(repo, CleanScope.UNTRACKED, listOf("*.txt", "we\"ird.txt", "tab\there.txt")).getOrThrow()
            assertTrue(Files.exists(repo.resolve("a.txt")), "the star name must not act as a pattern")
            assertFalse(Files.exists(repo.resolve("*.txt")))
            assertFalse(Files.exists(repo.resolve("we\"ird.txt")))
            assertFalse(Files.exists(repo.resolve("tab\there.txt")))
        }

    @Test
    fun `thousands of paths are cleaned in as many commands as the command line needs`(sandbox: GitSandbox) =
        runBlocking<Unit> {
            val rig = Rig(sandbox)
            val repo = repo(sandbox) {
                repeat(1500) { i -> it.write("many/file-number-$i-with-a-longish-name.txt", "") }
                repeat(40) { i -> it.write("sibling-$i.txt", "") }
            }
            val paths = rig.clean.preview(repo).getOrThrow().entries.map { it.path }.filter { it.startsWith("sibling") }
            rig.clean.clean(repo, CleanScope.UNTRACKED, paths + "many/").getOrThrow()
            assertEquals(
                listOf("tracked.txt"),
                Files.list(repo).use { l ->
                    l.map { it.fileName.toString() }.filter {
                        it !=
                            ".git"
                    }.toList()
                },
            )
        }

    @Test
    fun `outside a repository both fail with git's error`(sandbox: GitSandbox) = runBlocking<Unit> {
        val rig = Rig(sandbox)
        val plain = sandbox.dir("plain")
        assertIs<Result.Failure>(rig.clean.preview(plain))
        assertIs<Result.Failure>(rig.ignore.preview(plain, listOf("x")))
        assertIs<Result.Failure>(rig.ignore.add(plain, IgnoreTarget.Gitignore(), listOf("x")))
    }
}
