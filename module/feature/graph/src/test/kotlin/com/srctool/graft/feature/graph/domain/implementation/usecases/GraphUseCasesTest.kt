// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.domain.implementation.usecases

import com.srctool.graft.data.git.api.models.AheadBehind
import com.srctool.graft.data.git.api.models.AuthorType
import com.srctool.graft.data.git.api.models.CommitDetails
import com.srctool.graft.data.git.api.models.CommitLog
import com.srctool.graft.data.git.api.models.CommitRow
import com.srctool.graft.data.git.api.models.LogQuery
import com.srctool.graft.data.git.api.models.LogScope
import com.srctool.graft.data.git.api.models.MergePreview
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.People
import com.srctool.graft.data.git.api.models.Person
import com.srctool.graft.data.git.api.models.Ref
import com.srctool.graft.data.git.api.models.ReflogEntry
import com.srctool.graft.data.git.api.models.ReflogPage
import com.srctool.graft.data.git.api.repositories.CommitRepository
import com.srctool.graft.data.git.api.repositories.CompareRepository
import com.srctool.graft.data.git.api.repositories.RefRepository
import com.srctool.graft.data.git.api.repositories.ReflogRepository
import com.srctool.graft.feature.graph.domain.api.models.CompareSummary
import com.srctool.graft.feature.graph.domain.api.models.GraphFilter
import com.srctool.graft.feature.graph.domain.api.models.GraphLoad
import com.srctool.graft.feature.graph.domain.api.models.GraphSearch
import com.srctool.graft.feature.graph.domain.api.models.TimelineEntry
import com.srctool.graft.feature.graph.domain.api.models.TimelineSource
import com.srctool.graft.feature.graph.domain.api.usecases.GraphMatch
import com.srctool.sinew.models.ErrorMessage
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.SinewException
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphUseCasesTest {
    private val repo = Paths.get("/repo")

    private fun id(n: Int): ObjectId = ObjectId("%040x".format(n))

    @Test
    fun `solo wins over hiding, and hiding everything soloed shows all`() {
        assertEquals(LogScope.All(), GraphFilter().scope)
        assertEquals(LogScope.All(setOf("refs/heads/a")), GraphFilter(hiddenRefs = setOf("refs/heads/a")).scope)
        assertEquals(
            LogScope.Only(setOf("refs/heads/b")),
            GraphFilter(hiddenRefs = setOf("refs/heads/a"), soloRefs = setOf("refs/heads/a", "refs/heads/b")).scope,
        )
        assertEquals(
            LogScope.All(setOf("refs/heads/a")),
            GraphFilter(hiddenRefs = setOf("refs/heads/a"), soloRefs = setOf("refs/heads/a")).scope,
        )
    }

    @Test
    fun `refs that no longer exist drop out of the filter`() {
        val filter = GraphFilter(setOf("refs/heads/gone", "refs/heads/kept"), setOf("refs/heads/deleted"))
        assertEquals(GraphFilter(hiddenRefs = setOf("refs/heads/kept")), filter.prunedTo(setOf("refs/heads/kept")))
    }

    @Test
    fun `the graph is loaded over the filter's refs`() = runTest {
        val commits = RecordingCommits(LaneLayoutBenchmarkTest.synthetic(10))
        val load = GitLoadGraphUseCase(commits, ::LaneLayout, StandardTestDispatcher(testScheduler))
        load.load(repo, GraphFilter(soloRefs = setOf("refs/heads/main"))).toList()
        assertEquals(listOf<LogScope>(LogScope.Only(setOf("refs/heads/main"))), commits.scopes)
    }

    @Test
    fun `search sends matches in batches, then the total`() = runTest {
        val rows = LaneLayoutBenchmarkTest.synthetic(500)
        val commits = RecordingCommits(rows)
        val steps =
            GitSearchGraphUseCase(commits, StandardTestDispatcher(testScheduler))
                .search(repo, LogQuery(message = "x"), GraphFilter(hiddenRefs = setOf("refs/heads/old")))
                .toList()
        val found = steps.filterIsInstance<GraphSearch.Found>().flatMap { it.ids }
        assertEquals(rows.map { it.id }, found)
        assertEquals(GraphSearch.Done(500), steps.last())
        assertEquals(listOf<LogScope>(LogScope.All(setOf("refs/heads/old"))), commits.scopes)
    }

    @Test
    fun `a failing search keeps the matches before it`() = runTest {
        val rows = LaneLayoutBenchmarkTest.synthetic(3)
        val failing =
            flow {
                rows.forEach { emit(it) }
                throw IllegalStateException("git died")
            }
        val steps =
            GitSearchGraphUseCase(RecordingCommits(emptyList(), failing), StandardTestDispatcher(testScheduler))
                .search(repo, LogQuery(message = "x"))
                .toList()
        assertEquals(rows.map { it.id }, steps.filterIsInstance<GraphSearch.Found>().flatMap { it.ids })
        assertEquals("git died", assertIs<GraphSearch.Failed>(steps.last()).error.message)
    }

    @Test
    fun `hash prefixes find rows, anything else finds none`() = runTest {
        // Real-looking, distinct hashes: the synthetic ids are 1, 2, 3… and share every leading digit.
        val sha = MessageDigest.getInstance("SHA-1")
        fun spread(id: ObjectId) = ObjectId(sha.digest(id.hex.toByteArray()).joinToString("") { "%02x".format(it) })
        val rows = LaneLayoutBenchmarkTest.synthetic(50).map {
            it.copy(id = spread(it.id), parents = it.parents.map(::spread))
        }
        val graph =
            GitLoadGraphUseCase(RecordingCommits(rows), ::LaneLayout, StandardTestDispatcher(testScheduler))
                .load(repo)
                .toList()
                .last { it is GraphLoad.Loaded }
                .graph
        val target = rows[17].id.hex
        val row17 = (0 until graph.size).single { graph[it].commit.id.hex == target }
        assertEquals(listOf(row17), GraphMatch.hashPrefix(graph, target.take(10).uppercase()))
        assertEquals(listOf(row17), GraphMatch.hashPrefix(graph, target))
        assertEquals(emptyList(), GraphMatch.hashPrefix(graph, target.take(3)))
        assertEquals(emptyList(), GraphMatch.hashPrefix(graph, "not a hash"))
        assertFalse(GraphMatch.isHashPrefix("main"))
    }

    @Test
    fun `author types are matched by the commit's type`() {
        val bot = LaneLayoutBenchmarkTest.synthetic(1).single().copy(authorType = AuthorType.BOT)
        assertTrue(GraphMatch.authorType(bot, setOf(AuthorType.BOT, AuthorType.AI_ASSISTED)))
        assertFalse(GraphMatch.authorType(bot, setOf(AuthorType.PERSON)))
    }

    @Test
    fun `the compare summary reads ahead and behind from the compared branch's side`() = runTest {
        val summary = GitCompareSummaryUseCase(FakeCompare(AheadBehind(ahead = 5, behind = 12), id(1)))
        assertEquals(
            CompareSummary("main", "feature", ahead = 5, behind = 12, mergeBase = id(1)),
            summary.summary(repo, "main", "feature").getOrThrow(),
        )
        val unrelated = GitCompareSummaryUseCase(FakeCompare(AheadBehind(3, 4), null))
        assertNull(unrelated.summary(repo, "main", "orphan").getOrThrow().mergeBase)
    }

    @Test
    fun `the timeline merges HEAD and branches newest first, one entry per move`() = runTest {
        val reflogs =
            FakeReflogs(
                "HEAD" to listOf(
                    entry(50, 3, 4, "checkout: moving from main to topic"),
                    entry(40, 2, 3, "commit: two"),
                ),
                "refs/heads/main" to listOf(
                    entry(40, 2, 3, "commit: two"),
                    entry(30, null, 2, "commit (initial): one"),
                ),
                "refs/heads/topic" to listOf(entry(45, 3, 4, "branch: Created from HEAD")),
            )
        val timeline = GitReflogTimelineUseCase(
            reflogs,
            FakeRefs("refs/heads/topic", "refs/heads/main"),
        ).open(repo).getOrThrow()
        val entries = timeline.next(10).getOrThrow()
        assertEquals(listOf(50L, 45L, 40L, 30L), entries.map { it.time })
        assertEquals(listOf("HEAD", "refs/heads/main"), entries[2].refs)
        assertEquals(listOf("refs/heads/main"), entries[3].refs)
        assertNull(entries[3].oldId)
        assertTrue(entries.all { it.source == TimelineSource.GIT })
        assertTrue(timeline.exhausted)
        assertTrue(timeline.enabled)
    }

    @Test
    fun `the timeline pages through long reflogs without losing or repeating entries`() = runTest {
        val head = (1..250).map { entry(1000L - it, it, it + 1, "commit: $it") }
        val main = (1..250).map { entry(1000L - it, it, it + 1, "commit: $it") }
        val timeline =
            GitReflogTimelineUseCase(
                FakeReflogs("HEAD" to head, "refs/heads/main" to main),
                FakeRefs("refs/heads/main"),
            )
                .open(repo)
                .getOrThrow()
        val read = ArrayList<TimelineEntry>()
        while (!timeline.exhausted) read += timeline.next(30).getOrThrow()
        assertEquals(250, read.size)
        assertEquals(read.map { it.time }.sortedDescending(), read.map { it.time })
        assertTrue(read.all { it.refs == listOf("HEAD", "refs/heads/main") })
    }

    @Test
    fun `a timeline with reflogs off says so, and a failing ref fails the page`() = runTest {
        val off = FakeReflogs("HEAD" to emptyList(), enabled = false)
        val quiet = GitReflogTimelineUseCase(off, FakeRefs()).open(repo).getOrThrow()
        assertFalse(quiet.enabled)
        assertEquals(emptyList(), quiet.next(5).getOrThrow())

        val broken = GitReflogTimelineUseCase(
            FakeReflogs("HEAD" to emptyList()),
            FakeRefs(),
        ).open(repo, listOf("refs/heads/x"))
        assertIs<Result.Failure>(broken.getOrThrow().next(5))
    }

    private fun entry(time: Long, old: Int?, new: Int, message: String) = ReflogEntry(
        ref = "",
        index = 0,
        oldId = old?.let(::id),
        newId = id(new),
        time = time,
        offsetMinutes = 0,
        actor = Person("Ann", "ann@example.invalid"),
        message = message,
        subject = message.substringAfter(": "),
    )
}

private class RecordingCommits(rows: List<CommitRow>, private val flow: Flow<CommitRow> = rows.asFlow()) :
    CommitRepository {
    val scopes = ArrayList<LogScope>()

    override fun log(repository: Path, scope: LogScope): CommitLog {
        scopes += scope
        return CommitLog(flow, People())
    }

    override fun search(repository: Path, query: LogQuery, scope: LogScope): CommitLog {
        scopes += scope
        return CommitLog(flow, People())
    }

    override suspend fun details(repository: Path, id: ObjectId): Result<CommitDetails> = error("not used")
}

private class FakeCompare(private val counts: AheadBehind, private val base: ObjectId?) : CompareRepository {
    override fun commits(repository: Path, from: String, to: String): CommitLog = error("not used")

    override suspend fun aheadBehind(repository: Path, base: String, compare: String): Result<AheadBehind> =
        Result.Success(counts)

    override suspend fun mergeBase(repository: Path, a: String, b: String): Result<ObjectId?> = Result.Success(base)

    override suspend fun mergePreview(repository: Path, into: String, from: String): Result<MergePreview> =
        error("not used")
}

private class FakeRefs(private vararg val branches: String) : RefRepository {
    override suspend fun refs(repository: Path): Result<List<Ref>> = Result.Success(
        branches.map { Ref(name = it, target = ObjectId("0".repeat(40))) } +
            Ref("refs/tags/v1", ObjectId("1".repeat(40))),
    )
}

/** Reflogs held in memory, paged like git's: [skip] then [limit], newest first. A ref it doesn't hold fails. */
private class FakeReflogs(vararg logs: Pair<String, List<ReflogEntry>>, private val enabled: Boolean = true) :
    ReflogRepository {
    private val logs = logs.toMap()

    override suspend fun entries(repository: Path, ref: String, skip: Int, limit: Int): Result<ReflogPage> {
        val log = logs[ref] ?: return Result.Failure(TestGitError("unknown ref $ref"))
        val page = log.drop(skip).take(limit).map { it.copy(ref = ref) }
        return Result.Success(ReflogPage(page, hasMore = skip + page.size < log.size))
    }

    override suspend fun enabled(repository: Path): Result<Boolean> = Result.Success(enabled)
}

/** A failure as a repository returns one. */
private class TestGitError(message: String) : SinewException("GIT", ExceptionLayer.Repository, "TEST") {
    override val typeCode: String = "EXIT"
    override val errorMessage: ErrorMessage = ErrorMessage.Local(key = "test", fallback = message)
}
