// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.presentation.graph

import com.srctool.graft.data.app.api.models.RepositoryUiState
import com.srctool.graft.data.app.api.models.Saved
import com.srctool.graft.data.app.api.models.SavedStatus
import com.srctool.graft.data.app.api.repositories.RepositoryUiStateRepository
import com.srctool.graft.data.git.api.models.AuthorType
import com.srctool.graft.data.git.api.models.CommitDetails
import com.srctool.graft.data.git.api.models.CommitLog
import com.srctool.graft.data.git.api.models.CommitRow
import com.srctool.graft.data.git.api.models.Head
import com.srctool.graft.data.git.api.models.LogQuery
import com.srctool.graft.data.git.api.models.LogScope
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.People
import com.srctool.graft.data.git.api.models.Person
import com.srctool.graft.data.git.api.models.Ref
import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.data.git.api.models.RepositorySummary
import com.srctool.graft.data.git.api.repositories.CommitRepository
import com.srctool.graft.feature.graph.domain.api.models.GraphRow
import com.srctool.graft.feature.graph.domain.api.models.LaidOutGraph
import com.srctool.graft.feature.graph.domain.implementation.usecases.LaneLayout
import com.srctool.graft.presentation.session.RepoSnapshot
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.session.SnapshotPart
import com.srctool.sinew.models.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import java.nio.file.Path
import java.nio.file.Paths

/** A small history: a merge of `feature` into `main`, a tag, a root; a bot wrote one commit. */
internal object Sample {
    /** Commit ids that differ from their first digit, so a 7-character prefix names one commit. */
    fun id(n: Int): ObjectId = ObjectId(n.toString(16).repeat(40))

    val now: Long = 1_800_000_000

    val people =
        listOf(
            Person("Dwiki Riyadi", "dwiki@example.invalid"),
            Person("Ann Lee", "ann@example.invalid"),
            Person("dependabot[bot]", "bot@example.invalid"),
        )

    val rows: List<CommitRow> =
        listOf(
            row(5, listOf(4, 3), "Merge feature/login", author = 0, hoursAgo = 2),
            row(4, listOf(2), "Fix login timeout", author = 0, hoursAgo = 5),
            row(3, listOf(2), "Add retry", author = 1, hoursAgo = 26, type = AuthorType.AI_ASSISTED),
            row(2, listOf(1), "Update deps", author = 2, hoursAgo = 24 * 3, type = AuthorType.BOT),
            row(1, emptyList(), "Initial commit", author = 0, hoursAgo = 24 * 30),
        )

    private fun row(
        n: Int,
        parents: List<Int>,
        subject: String,
        author: Int,
        hoursAgo: Long,
        type: AuthorType = AuthorType.PERSON,
    ) = CommitRow(id(n), parents.map(::id), author, now - hoursAgo * 3600, author, now - hoursAgo * 3600, subject, type)

    val refs: List<Ref> =
        listOf(
            Ref("refs/heads/main", id(5), isHead = true, upstream = "refs/remotes/origin/main", ahead = 1, behind = 0),
            Ref("refs/remotes/origin/main", id(5)),
            Ref("refs/heads/feature/login", id(3)),
            Ref("refs/tags/v1.0", id(2)),
        )

    val location = RepositoryLocation(Paths.get("/repo"), Paths.get("/repo/.git"), Paths.get("/repo/.git"))

    fun snapshot(refs: List<Ref> = Sample.refs, dirty: Int? = 4, head: ObjectId? = id(5)) = RepoSnapshot(
        location,
        refs = refs,
        summary = RepositorySummary(
            Head.Branch("main", head),
            upstream = null,
            dirty = dirty != null,
            changedFiles =
            dirty ?: 0,
        ),
        loaded = setOf(SnapshotPart.REFS, SnapshotPart.STATUS),
    )

    /** [row]'s full details, as `CommitRepository.details` reads them. */
    fun details(row: CommitRow): CommitDetails {
        val author = people[row.authorIndex]
        val signature = com.srctool.graft.data.git.api.models.Signature(author.name, author.email, row.authorTime, 0)
        return CommitDetails(
            id = row.id,
            parents = row.parents,
            author = signature,
            committer = signature,
            message =
            row.subject +
                "\n\nThe login timed out after 30 s on slow networks.\nRaise it to 60 s.\n\nCo-authored-by: Ann Lee <ann@example.invalid>",
            trailers = listOf(
                com.srctool.graft.data.git.api.models.Trailer("Co-authored-by", "Ann Lee <ann@example.invalid>"),
            ),
            signature = com.srctool.graft.data.git.api.models.SignatureStatus.GOOD,
            files = sampleFiles,
        )
    }

    fun peopleIndex(): People = People().also { p -> people.forEach { p.intern(it) } }

    /** [rows] laid out as the graph use case would, for page tests that draw a state directly. */
    fun graph(rows: List<CommitRow> = Sample.rows): LaidOutGraph {
        val layout = LaneLayout()
        val laid = rows.map(layout::next)
        val people = peopleIndex()
        return object : LaidOutGraph {
            override val size: Int = laid.size
            override val widest: Int = layout.widest
            override val people: People = people

            override fun get(index: Int): GraphRow = laid[index]

            override fun through(row: Int, lane: Int): Int = layout.through(row, lane)
        }
    }
}

internal class FakeSession(initial: RepoSnapshot = Sample.snapshot()) : RepositorySession {
    val state = MutableStateFlow(initial)
    var writes = 0
    override val location: RepositoryLocation = Sample.location
    override val snapshot: StateFlow<RepoSnapshot> = state

    override fun refresh(parts: Set<SnapshotPart>) = Unit

    override suspend fun <T> write(block: suspend () -> T): T {
        writes++
        return block()
    }
}

/** Commits from [rows]; a search matches subjects containing the message. Records what git was asked. */
internal class FakeCommitRepository(var rows: List<CommitRow> = Sample.rows) : CommitRepository {
    val scopes = ArrayList<LogScope>()
    val firstParents = ArrayList<Boolean>()
    var failure: Throwable? = null
    var hasGraph = true
    var graphWrites = 0

    override fun log(repository: Path, scope: LogScope, firstParent: Boolean): CommitLog {
        scopes += scope
        firstParents += firstParent
        val error = failure
        val flow: Flow<CommitRow> =
            if (error == null) {
                rows.asFlow()
            } else {
                flow {
                    rows.take(2).forEach { emit(it) }
                    throw error
                }
            }
        return CommitLog(flow, Sample.peopleIndex())
    }

    override fun search(repository: Path, query: LogQuery, scope: LogScope): CommitLog =
        CommitLog(rows.filter { query.message != null && query.message!! in it.subject }.asFlow(), Sample.peopleIndex())

    var detailsFailure: Throwable? = null

    override suspend fun details(repository: Path, id: ObjectId): Result<CommitDetails> {
        detailsFailure?.let {
            return Result.Failure(it as? com.srctool.sinew.models.SinewException ?: FakeFailure(it.message ?: ""))
        }
        val row = rows.firstOrNull { it.id == id } ?: return Result.Failure(FakeFailure("no commit $id"))
        return Result.Success(Sample.details(row))
    }

    override suspend fun hasCommitGraph(repository: Path): Result<Boolean> = Result.Success(hasGraph)

    /** Branch, tag and hash-prefix names of [rows] and [Sample.refs]; anything else doesn't resolve. */
    override suspend fun resolve(repository: Path, revision: String): Result<ObjectId?> {
        Sample.refs.firstOrNull {
            it.name == revision || it.shortName == revision
        }?.let { return Result.Success(it.commit) }
        return Result.Success(rows.firstOrNull { it.id.hex.startsWith(revision) && revision.length >= 4 }?.id)
    }

    override suspend fun writeCommitGraph(repository: Path): Result<Unit> {
        graphWrites++
        hasGraph = true
        return Result.Success(Unit)
    }
}

internal class FakeUiState(var value: RepositoryUiState = RepositoryUiState()) : RepositoryUiStateRepository {
    override suspend fun read(commonDir: Path): Result<Saved<RepositoryUiState>> =
        Result.Success(Saved(value, SavedStatus.Current))

    override suspend fun update(
        commonDir: Path,
        change: (RepositoryUiState) -> RepositoryUiState,
    ): Result<RepositoryUiState> {
        value = change(value)
        return Result.Success(value)
    }
}

/** The repository's stashes; empty unless a test adds some. */
internal class FakeStashes(var entries: List<com.srctool.graft.data.git.api.models.StashEntry> = emptyList()) :
    com.srctool.graft.data.git.api.repositories.StashRepository {
    override suspend fun list(repository: Path): Result<List<com.srctool.graft.data.git.api.models.StashEntry>> =
        Result.Success(entries)
}

/** A stash made on "Fix login timeout" (row 1 of [Sample.rows]). */
internal val sampleStash =
    com.srctool.graft.data.git.api.models.StashEntry(
        index = 0,
        id = Sample.id(9),
        base = Sample.id(4),
        branch = "main",
        message = "WIP on main: 4444444 Fix login timeout",
        time = Sample.now - 3_600,
    )

/** A failure the fakes report, with a code like the data layer's. */
internal class FakeFailure(text: String) :
    com.srctool.sinew.models.SinewException("GIT", com.srctool.sinew.models.ExceptionLayer.Repository, "DETL"),
    com.srctool.graft.core.exception.CommandOutput {
    override val typeCode: String = "EXIT"
    override val errorMessage = com.srctool.sinew.models.ErrorMessage.Local(
        "test.failed",
        emptyMap(),
        "Git couldn't read it.",
    )
    override val argv: List<String> = listOf("git", "show")
    override val exitCode: Int? = 128
    override val stderr: String = "fatal: $text"
}

/** Every changed file kind the details and diff screens show. */
internal val sampleFiles: List<com.srctool.graft.data.git.api.models.ChangedFile> =
    listOf(
        changed(
            com.srctool.graft.data.git.api.models.FileStatus.MODIFIED,
            "src/main/kotlin/login/Login.kt",
            add = 12,
            del = 3,
        ),
        changed(
            com.srctool.graft.data.git.api.models.FileStatus.RENAMED,
            "src/main/kotlin/login/Retry.kt",
            old = "src/main/kotlin/login/Old.kt",
            add = 1,
            del = 1,
        ),
        changed(
            com.srctool.graft.data.git.api.models.FileStatus.ADDED,
            "src/main/kotlin/login/Backoff.kt",
            add = 40,
            del = 0,
        ),
        changed(com.srctool.graft.data.git.api.models.FileStatus.MODIFIED, "docs/logo.png", add = null, del = null),
        changed(com.srctool.graft.data.git.api.models.FileStatus.DELETED, "README.old", add = 0, del = 7),
    )

private fun changed(
    status: com.srctool.graft.data.git.api.models.FileStatus,
    path: String,
    old: String? = null,
    add: Int?,
    del: Int?,
) = com.srctool.graft.data.git.api.models.ChangedFile(
    status,
    path,
    old,
    similarity = if (old != null) 90 else null,
    oldMode = if (status == com.srctool.graft.data.git.api.models.FileStatus.ADDED) "000000" else "100644",
    newMode = if (status == com.srctool.graft.data.git.api.models.FileStatus.DELETED) "000000" else "100644",
    additions = add,
    deletions = del,
    oldObject = if (status ==
        com.srctool.graft.data.git.api.models.FileStatus.ADDED
    ) {
        null
    } else {
        ObjectId("e".repeat(40))
    },
    newObject = if (status ==
        com.srctool.graft.data.git.api.models.FileStatus.DELETED
    ) {
        null
    } else {
        ObjectId("f".repeat(40))
    },
)
