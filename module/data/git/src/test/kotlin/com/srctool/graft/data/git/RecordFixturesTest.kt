// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.graft.data.git.api.models.DiffTarget
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.implementation.source.local.ChangedFilesCommand
import com.srctool.graft.data.git.implementation.source.local.DetailsCommand
import com.srctool.graft.data.git.implementation.source.local.LogCommand
import com.srctool.graft.data.git.implementation.source.local.PatchCommand
import com.srctool.graft.data.git.implementation.source.local.ReflogCommand
import com.srctool.graft.data.git.implementation.source.local.RefsCommand
import com.srctool.graft.data.git.implementation.source.local.RemotesCommand
import com.srctool.graft.data.git.implementation.source.local.StashListCommand
import com.srctool.graft.data.git.implementation.source.local.StatusSummaryCommand
import com.srctool.graft.data.git.implementation.source.local.WorkingTreeCommand
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.Test

/** Re-records `fixtures/git/` from the generated repositories. Runs only with `./gradlew … -PrecordFixtures`. */
@ExtendWith(GitSandboxExtension::class)
class RecordFixturesTest {
    @Test
    fun `record the graph repository`(sandbox: GitSandbox) = runBlocking {
        assumeTrue(Fixtures.recording, "set -PrecordFixtures to re-record")
        val repo = GraphRepo.build(sandbox)
        val runner = sandbox.runner()
        Fixtures.write("log", "graph", runner.run(RawBytesCommand(LogCommand().args), repo).getOrThrow())
        Fixtures.write("refs", "graph", runner.run(RawBytesCommand(RefsCommand().args), repo).getOrThrow())
        val authors = AuthorsRepo.build(sandbox)
        Fixtures.write("log", "authors", runner.run(RawBytesCommand(LogCommand().args), authors).getOrThrow())
    }

    @Test
    fun `record status summaries`(sandbox: GitSandbox) = runBlocking {
        assumeTrue(Fixtures.recording, "set -PrecordFixtures to re-record")
        val runner = sandbox.runner()
        StatusRepos.build(sandbox).forEach { (case, repo) ->
            Fixtures.write("status", case, runner.run(RawBytesCommand(StatusSummaryCommand().args), repo).getOrThrow())
        }
    }

    @Test
    fun `record commit details, changed files and patches`(sandbox: GitSandbox) = runBlocking {
        assumeTrue(Fixtures.recording, "set -PrecordFixtures to re-record")
        val built = DiffRepo.build(sandbox)
        val runner = sandbox.runner()
        suspend fun record(command: String, case: String, args: List<String>) =
            Fixtures.write(command, case, runner.run(RawBytesCommand(args), built.path).getOrThrow())
        record("details", "change", DetailsCommand(ObjectId(built.change)).args)
        listOf("base" to built.base, "change" to built.change, "latin1" to built.latin1).forEach { (case, id) ->
            val target = DiffTarget.Commit(ObjectId(id))
            record("files", case, ChangedFilesCommand(target).args)
            record("patch", case, PatchCommand(target).args)
        }
    }

    @Test
    fun `record reflogs, stashes and remotes`(sandbox: GitSandbox) = runBlocking {
        assumeTrue(Fixtures.recording, "set -PrecordFixtures to re-record")
        val runner = sandbox.runner()
        val history = ReflogRepos.history(sandbox)
        Fixtures.write(
            "reflog",
            "history",
            runner.run(RawBytesCommand(ReflogCommand("HEAD", 0, 100).args), history).getOrThrow(),
        )
        val stashes = ReflogRepos.stashes(sandbox)
        Fixtures.write("stash", "list", runner.run(RawBytesCommand(StashListCommand().args), stashes).getOrThrow())
        val remotes = ReflogRepos.remotes(sandbox)
        Fixtures.write(
            "remote",
            "credentials",
            runner.run(RawBytesCommand(RemotesCommand().args), remotes).getOrThrow(),
        )
    }

    @Test
    fun `record working tree status`(sandbox: GitSandbox) = runBlocking {
        assumeTrue(Fixtures.recording, "set -PrecordFixtures to re-record")
        val runner = sandbox.runner()
        suspend fun record(case: String, repo: java.nio.file.Path, includeIgnored: Boolean) = Fixtures.write(
            "worktree",
            case,
            runner.run(RawBytesCommand(WorkingTreeCommand(includeIgnored).args), repo).getOrThrow(),
        )
        WorkingTreeRepos.build(sandbox).forEach { (case, repo) ->
            record(case, repo, includeIgnored = false)
            // The same mixed repository with its ignored paths listed.
            if (case == "mixed") record("mixed-ignored", repo, includeIgnored = true)
        }
    }
}
