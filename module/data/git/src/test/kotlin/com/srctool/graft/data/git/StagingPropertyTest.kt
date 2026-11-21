// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.graft.core.git.testing.RawGitCommand
import com.srctool.graft.data.git.api.models.ChangeSelection
import com.srctool.graft.data.git.api.models.FileSelection
import com.srctool.graft.data.git.api.models.FileSelection.Scope
import com.srctool.graft.data.git.api.models.LineRange
import com.srctool.graft.data.git.implementation.repositories.GitStagingRepository
import com.srctool.graft.data.git.implementation.source.local.RawLine
import com.srctool.graft.data.git.implementation.source.local.RawPatchParser
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Random edits, random selections: whatever lines are staged, the index must hold exactly the old file with those
 * changes applied, and unstaging everything must bring the index back to `HEAD`. The expected index is worked out
 * here from the diff's lines, not from the patch the repository builds.
 */
@ExtendWith(GitSandboxExtension::class)
class StagingPropertyTest {
    private fun edit(random: Random, old: List<String>): List<String> {
        val out = ArrayList<String>()
        old.forEachIndexed { i, line ->
            when (random.nextInt(8)) {
                0 -> out += "edited $i"

                1 -> Unit

                2 -> {
                    out += line
                    out += "inserted after $i"
                    if (random.nextBoolean()) out += "and another $i"
                }

                else -> out += line
            }
        }
        return out
    }

    /** The index after staging [picked] changes: every hunk's lines walked in order, old lines between hunks copied. */
    private fun expected(old: List<String>, diff: ByteArray, picked: Set<Pair<Int, Int>>): List<String> {
        val hunks = RawPatchParser.parse(diff).single().hunks
        val out = ArrayList<String>()
        var next = 1
        hunks.forEachIndexed { h, hunk ->
            val first = if (hunk.oldCount == 0) hunk.oldStart + 1 else hunk.oldStart
            while (next < first) out += old[next++ - 1]
            hunk.lines.forEachIndexed { l, line ->
                val text = line.content.toString(Charsets.ISO_8859_1)
                when (line.kind) {
                    RawLine.Kind.CONTEXT -> out += text
                    RawLine.Kind.REMOVED -> if ((h to l) !in picked) out += text
                    RawLine.Kind.ADDED -> if ((h to l) in picked) out += text
                }
            }
            next = first + hunk.oldCount
        }
        while (next <= old.size) out += old[next++ - 1]
        return out
    }

    @Test
    fun `staging random lines gives exactly those changes, and unstaging them all gives HEAD back`(
        sandbox: GitSandbox,
    ) = runBlocking {
        val staging = GitStagingRepository(sandbox.runner())
        repeat(30) { round ->
            val random = Random(round)
            val old = (1..random.nextInt(15, 60)).map { "line $it" }
            val repo = sandbox.repo("prop-$round") { commit("one") { file("f.txt", old.joinToString("") { "$it\n" }) } }
            val new = edit(random, old)
            Files.writeString(repo.resolve("f.txt"), new.joinToString("") { "$it\n" })
            val diff = sandbox.runner().run(RawGitCommand(listOf("diff", "-U3", "--no-color"), mutates = false), repo)
                .getOrThrow().toByteArray(Charsets.ISO_8859_1)
            val hunks = RawPatchParser.parse(diff).singleOrNull()?.hunks.orEmpty()
            if (hunks.isEmpty()) return@repeat

            val picked = HashSet<Pair<Int, Int>>()
            val ranges = ArrayList<LineRange>()
            hunks.forEachIndexed { h, hunk ->
                hunk.lines.forEachIndexed { l, line ->
                    if (line.kind != RawLine.Kind.CONTEXT && random.nextBoolean()) {
                        picked += h to l
                        ranges += LineRange(h, l)
                    }
                }
            }
            staging.stage(repo, ChangeSelection(listOf(FileSelection("f.txt", Scope.Lines(ranges))))).getOrThrow()

            assertEquals(
                expected(old, diff, picked).joinToString("") { "$it\n" },
                sandbox.git(repo, "show", ":f.txt"),
                "round $round, picked ${picked.sortedBy { it.first * 1000 + it.second }}",
            )
            // The working tree never changes.
            assertEquals(new.joinToString("") { "$it\n" }, Files.readString(repo.resolve("f.txt")))

            val cached = sandbox.git(repo, "diff", "--cached", "-U3", "--no-color").toByteArray(Charsets.ISO_8859_1)
            val staged = RawPatchParser.parse(cached).singleOrNull()?.hunks.orEmpty()
            if (staged.isNotEmpty()) {
                val all = staged.indices.toSet()
                staging.unstage(repo, ChangeSelection(listOf(FileSelection("f.txt", Scope.Hunks(all))))).getOrThrow()
            }
            assertEquals("", sandbox.git(repo, "diff", "--cached", "--stat"), "round $round: unstaged all")
        }
    }
}
