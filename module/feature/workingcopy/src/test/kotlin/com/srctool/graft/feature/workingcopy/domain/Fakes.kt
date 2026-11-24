// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain

import com.srctool.graft.data.app.api.models.ChangelistBook
import com.srctool.graft.data.app.api.models.RepositoryUiState
import com.srctool.graft.data.app.api.models.Saved
import com.srctool.graft.data.app.api.models.SavedStatus
import com.srctool.graft.data.app.api.repositories.ChangelistRepository
import com.srctool.graft.data.app.api.repositories.RepositoryUiStateRepository
import com.srctool.graft.data.git.api.models.Head
import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.data.git.api.models.RepositorySummary
import com.srctool.graft.data.git.api.models.WorkingBlocks
import com.srctool.graft.data.git.api.models.WorkingTree
import com.srctool.graft.data.git.api.repositories.BlockSource
import com.srctool.graft.data.git.api.repositories.ChangeBlocksRepository
import com.srctool.graft.data.git.api.repositories.RepositoryAccessRepository
import com.srctool.graft.data.git.api.repositories.WorkingTreeRepository
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingCopy
import com.srctool.sinew.models.ErrorMessage
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.SinewException
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.nio.file.Paths

/**
 * An implementation of [T] whose every call throws, so a fake can delegate to it and override only what a test uses:
 * a call the test didn't expect fails loudly instead of returning something plausible.
 */
internal inline fun <reified T : Any> unsupported(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        throw UnsupportedOperationException("${T::class.simpleName}.${method.name} was not expected")
    } as T

/** A failure to hand back from a fake. */
internal class TestFailure(function: String = "STAT") : SinewException("GIT", ExceptionLayer.Repository, function) {
    override val typeCode: String = "EXIT"
    override val errorMessage: ErrorMessage = ErrorMessage.Local(key = "test.failure", fallback = "failed")
}

internal fun failure(function: String = "STAT"): Result.Failure = Result.Failure(TestFailure(function))

internal val REPO: Path = Paths.get("/work/repo")
internal val COPY = WorkingCopy(REPO, "repo")
internal val LOCATION = RepositoryLocation(REPO, Paths.get("/work/repo/.git"), Paths.get("/work/repo/.git"))

internal class FakeAccess(var head: Head = Head.Branch("main", null)) : RepositoryAccessRepository by unsupported() {
    override suspend fun locate(folder: Path): Result<RepositoryLocation> = Result.Success(LOCATION)

    override suspend fun summary(workTree: Path): Result<RepositorySummary> =
        Result.Success(RepositorySummary(head, null, dirty = false))
}

internal class FakeWorkingTree(var tree: Result<WorkingTree>) : WorkingTreeRepository by unsupported() {
    var reads = 0

    override suspend fun status(repository: Path, includeIgnored: Boolean): Result<WorkingTree> {
        reads++
        return tree
    }
}

internal class FakeBlocks(var blocks: Result<WorkingBlocks>) : ChangeBlocksRepository {
    var reads = 0

    override suspend fun read(repository: Path, source: BlockSource, contextLines: Int): Result<WorkingBlocks> {
        reads++
        return blocks
    }
}

/** Keeps one book in memory, counting the writes a refresh makes. */
internal class FakeChangelists(var book: ChangelistBook = ChangelistBook()) : ChangelistRepository {
    var writes = 0

    override suspend fun read(gitDir: Path): Result<Saved<ChangelistBook>> =
        Result.Success(Saved(book, SavedStatus.Current))

    override suspend fun update(gitDir: Path, change: (ChangelistBook) -> ChangelistBook): Result<ChangelistBook> {
        book = change(book)
        writes++
        return Result.Success(book)
    }
}

internal class FakeUiState(var state: RepositoryUiState = RepositoryUiState()) : RepositoryUiStateRepository {
    override suspend fun read(commonDir: Path): Result<Saved<RepositoryUiState>> =
        Result.Success(Saved(state, SavedStatus.Current))

    override suspend fun update(
        commonDir: Path,
        change: (RepositoryUiState) -> RepositoryUiState,
    ): Result<RepositoryUiState> {
        state = change(state)
        return Result.Success(state)
    }
}
