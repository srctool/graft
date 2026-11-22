// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.repositories

import com.srctool.graft.core.git.CommitProblem
import com.srctool.graft.core.git.GitCommitRefusedException
import com.srctool.graft.core.git.GitNotFoundException
import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.core.git.TempIndex
import com.srctool.graft.data.git.api.models.ChangeSelection
import com.srctool.graft.data.git.api.models.CoAuthor
import com.srctool.graft.data.git.api.models.CommitDraft
import com.srctool.graft.data.git.api.models.CommitMessageText
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.OperationKind
import com.srctool.graft.data.git.api.repositories.CommittingRepository
import com.srctool.graft.data.git.implementation.source.local.CommitCommand
import com.srctool.graft.data.git.implementation.source.local.HeadMessageCommand
import com.srctool.graft.data.git.implementation.source.local.InsideRepositoryCommand
import com.srctool.graft.data.git.implementation.source.local.ReadHeadTreeCommand
import com.srctool.graft.data.git.implementation.source.local.RecentAuthorsCommand
import com.srctool.graft.data.git.implementation.source.local.ResetPathsCommand
import com.srctool.graft.data.git.implementation.source.local.ResolveCommand
import com.srctool.graft.data.git.implementation.source.local.StagedPathsCommand
import com.srctool.graft.data.git.implementation.source.local.TemplatePathCommand
import com.srctool.graft.data.git.implementation.source.local.pathspecInput
import com.srctool.sinew.models.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal class GitCommittingRepository(
    private val runner: GitRunner,
    private val staging: GitStagingRepository,
    private val recorder: MutationRecorder,
) : CommittingRepository {
    override suspend fun commit(
        repository: Path,
        draft: CommitDraft,
        selection: ChangeSelection?,
        contextLines: Int,
    ): Result<ObjectId> {
        refusal(draft)?.let { return Result.Failure(it) }
        (runner.run(InsideRepositoryCommand(), repository) as? Result.Failure)?.let { return it }
        if (selection == null) {
            if (!draft.allowEmpty) {
                when (val staged = runner.run(StagedPathsCommand(), repository)) {
                    is Result.Failure -> return staged
                    is Result.Success -> if (!staged.data) return nothingToCommit()
                }
            }
            return committed(repository, CommitCommand(draft, amend = false), draft, OperationKind.COMMIT)
        }
        return commitSelection(repository, draft, selection, contextLines)
    }

    override suspend fun amend(repository: Path, draft: CommitDraft, withStaged: Boolean): Result<ObjectId> {
        refusal(draft)?.let { return Result.Failure(it) }
        (runner.run(InsideRepositoryCommand(), repository) as? Result.Failure)?.let { return it }
        return committed(
            repository,
            CommitCommand(draft, amend = true, withStaged = withStaged),
            draft,
            OperationKind.AMEND,
        )
    }

    /**
     * HEAD plus the selection, built in a scratch index and committed from it; then the real index is told the
     * selected files now equal the new commit, which is what `git commit --only` leaves behind.
     */
    private suspend fun commitSelection(
        repository: Path,
        draft: CommitDraft,
        selection: ChangeSelection,
        contextLines: Int,
    ): Result<ObjectId> {
        val gitDir =
            recorder.location(repository)?.gitDir
                ?: return Result.Failure(
                    GitNotFoundException("CMIT", listOf("rev-parse"), 128, "fatal: not a git repository"),
                )
        val made =
            TempIndex.scope(gitDir) { scratch ->
                val unborn = (runner.run(ResolveCommand("HEAD"), repository) as? Result.Success)?.data == null
                (runner.run(ReadHeadTreeCommand(unborn, scratch.environment), repository) as? Result.Failure)
                    ?.let { return@scope it }
                (staging.stageInto(repository, selection, contextLines, scratch.environment) as? Result.Failure)
                    ?.let { return@scope it }
                if (!draft.allowEmpty) {
                    when (val staged = runner.run(StagedPathsCommand(scratch.environment), repository)) {
                        is Result.Failure -> return@scope staged
                        is Result.Success -> if (!staged.data) return@scope nothingToCommit()
                    }
                }
                committed(
                    repository,
                    CommitCommand(draft, amend = false, index = scratch.environment),
                    draft,
                    OperationKind.COMMIT,
                )
            }
        if (made is Result.Failure) return made
        val files = selection.files.flatMap { listOfNotNull(it.path, it.originalPath) }
        (runner.run(ResetPathsCommand(), repository, pathspecInput(files)) as? Result.Failure)?.let { return it }
        return made
    }

    private suspend fun committed(
        repository: Path,
        command: CommitCommand,
        draft: CommitDraft,
        kind: OperationKind,
    ): Result<ObjectId> {
        val done = recorder.run(command, repository, kind, input = draft.message.toByteArray(Charsets.UTF_8))
        if (done is Result.Failure) return done
        return when (val head = runner.run(ResolveCommand("HEAD"), repository)) {
            is Result.Failure -> head
            is Result.Success -> head.data?.let { Result.Success(it) } ?: nothingToCommit()
        }
    }

    override suspend fun previousMessage(repository: Path): Result<CommitMessageText?> {
        val head =
            when (val r = runner.run(ResolveCommand("HEAD"), repository)) {
                is Result.Failure -> return r
                is Result.Success -> r.data
            }
        if (head == null) return Result.Success(null)
        return when (val r = runner.run(HeadMessageCommand(), repository)) {
            is Result.Failure -> r
            is Result.Success -> Result.Success(CommitMessageText.of(r.data))
        }
    }

    override suspend fun template(repository: Path): Result<String?> {
        val path =
            when (val r = runner.run(TemplatePathCommand(), repository)) {
                is Result.Failure -> return r
                is Result.Success -> r.data ?: return Result.Success(null)
            }
        // Git reads a relative template from the working tree's root, wherever it was started.
        val file = Path.of(path).let { if (it.isAbsolute) it else repository.toAbsolutePath().resolve(it) }
        return withContext(Dispatchers.IO) {
            try {
                Result.Success(String(Files.readAllBytes(file), Charsets.UTF_8))
            } catch (_: IOException) {
                // A template that can't be read is no template; git itself would refuse to commit with an editor.
                Result.Success(null)
            }
        }
    }

    override suspend fun recentAuthors(repository: Path, limit: Int): Result<List<CoAuthor>> {
        val resolved = runner.run(ResolveCommand("HEAD"), repository)
        if (resolved is Result.Success && resolved.data == null) return Result.Success(emptyList())
        return when (val r = runner.run(RecentAuthorsCommand(SCAN), repository)) {
            is Result.Failure -> r

            is Result.Success ->
                Result.Success(
                    r.data.filter { it.email != "graft@localhost" }.distinctBy { it.email.lowercase() }.take(limit),
                )
        }
    }

    private fun refusal(draft: CommitDraft): GitCommitRefusedException? {
        if (draft.summary.isBlank()) return GitCommitRefusedException("CMIT", CommitProblem.NO_SUMMARY)
        val bad = draft.coAuthors.firstOrNull { CoAuthor.parse(it.toString()) != it }
        return bad?.let { GitCommitRefusedException("CMIT", CommitProblem.BAD_CO_AUTHOR, it.toString()) }
    }

    private fun nothingToCommit() = Result.Failure(GitCommitRefusedException("CMIT", CommitProblem.NOTHING_TO_COMMIT))

    private companion object {
        const val SCAN = 300
    }
}
