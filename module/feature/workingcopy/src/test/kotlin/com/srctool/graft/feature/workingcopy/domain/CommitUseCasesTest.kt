// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain

import com.srctool.graft.data.app.api.models.ChangesMode
import com.srctool.graft.data.git.api.models.ChangeSelection
import com.srctool.graft.data.git.api.models.CoAuthor
import com.srctool.graft.data.git.api.models.CommitDraft
import com.srctool.graft.data.git.api.models.CommitMessageText
import com.srctool.graft.data.git.api.models.FileSelection
import com.srctool.graft.data.git.api.models.GuardFinding
import com.srctool.graft.data.git.api.models.GuardOptions
import com.srctool.graft.data.git.api.models.GuardReport
import com.srctool.graft.data.git.api.models.GuardRule
import com.srctool.graft.data.git.api.models.GuardSeverity
import com.srctool.graft.data.git.api.models.GuardTarget
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.Snapshot
import com.srctool.graft.data.git.api.models.SnapshotResult
import com.srctool.graft.data.git.api.repositories.CommittingRepository
import com.srctool.graft.data.git.api.repositories.GuardRepository
import com.srctool.graft.data.git.api.repositories.LocalHistoryRepository
import com.srctool.graft.feature.workingcopy.domain.api.models.ChangeRow
import com.srctool.graft.feature.workingcopy.domain.api.models.RowStatus
import com.srctool.graft.feature.workingcopy.domain.api.usecases.BeforeRiskyOperation
import com.srctool.graft.feature.workingcopy.domain.api.usecases.CommitOutcome
import com.srctool.graft.feature.workingcopy.domain.api.usecases.CommitRequest
import com.srctool.graft.feature.workingcopy.domain.api.usecases.DraftKey
import com.srctool.graft.feature.workingcopy.domain.api.usecases.RiskyOperation
import com.srctool.graft.feature.workingcopy.domain.api.usecases.toSelection
import com.srctool.graft.feature.workingcopy.domain.implementation.usecases.FileDraftsUseCase
import com.srctool.graft.feature.workingcopy.domain.implementation.usecases.GuardedCommitUseCase
import com.srctool.graft.feature.workingcopy.domain.implementation.usecases.SnapshotUseCase
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Commit and amend with the guards in front, and what the use cases around them hand on. */
class CommitUseCasesTest {
    private val newId = ObjectId("c".repeat(40))
    private val draft = CommitDraft("Fix login")

    private fun finding(fingerprint: String, severity: GuardSeverity = GuardSeverity.BLOCK) =
        GuardFinding(GuardRule.SECRET, severity, "src/a.kt", 3, "ghp_****…a91F", "GitHub token", fingerprint)

    private class FakeCommitting(var result: Result<ObjectId>) : CommittingRepository by unsupported() {
        val commits = ArrayList<Triple<CommitDraft, ChangeSelection?, Int>>()
        val amends = ArrayList<Pair<CommitDraft, Boolean>>()

        override suspend fun commit(
            repository: Path,
            draft: CommitDraft,
            selection: ChangeSelection?,
            contextLines: Int,
        ): Result<ObjectId> {
            commits += Triple(draft, selection, contextLines)
            return result
        }

        override suspend fun amend(repository: Path, draft: CommitDraft, withStaged: Boolean): Result<ObjectId> {
            amends += draft to withStaged
            return result
        }

        override suspend fun previousMessage(repository: Path): Result<CommitMessageText?> =
            Result.Success(CommitMessageText("Old", "body"))

        override suspend fun template(repository: Path): Result<String?> = Result.Success(null)

        override suspend fun recentAuthors(repository: Path, limit: Int): Result<List<CoAuthor>> =
            Result.Success(listOf(CoAuthor("Ann Lee", "ann@example.invalid")))
    }

    private class FakeGuard(var report: Result<GuardReport>) : GuardRepository by unsupported() {
        val scans = ArrayList<GuardTarget>()
        val options = ArrayList<GuardOptions>()
        val allowed = ArrayList<String>()
        var allowFails = false

        override suspend fun scan(repository: Path, target: GuardTarget, options: GuardOptions): Result<GuardReport> {
            scans += target
            this.options += options
            return report
        }

        val always = ArrayList<Pair<String, Boolean>>()

        override suspend fun allowAlways(repository: Path, finding: GuardFinding, shared: Boolean): Result<Unit> {
            always += finding.fingerprint to shared
            return Result.Success(Unit)
        }

        override suspend fun allowOnce(repository: Path, finding: GuardFinding): Result<Unit> {
            if (allowFails) return failure("GARD")
            allowed += finding.fingerprint
            return Result.Success(Unit)
        }
    }

    private class Rig(val committing: FakeCommitting, val guard: FakeGuard) {
        val state = FakeUiState()
        val drafts = FileDraftsUseCase(FakeAccess(), state, FakeChangelists())
        val useCase = GuardedCommitUseCase(committing, guard, drafts)
    }

    private fun rig(report: GuardReport = GuardReport(emptyList()), commit: Result<ObjectId> = Result.Success(newId)) =
        Rig(FakeCommitting(commit), FakeGuard(Result.Success(report)))

    @Test
    fun `recent authors come from the repository, and allow always goes to the guard as asked`() = runTest {
        val rig = rig()
        val authors = (rig.useCase.recentAuthors(COPY) as Result.Success).data
        assertEquals(listOf(CoAuthor("Ann Lee", "ann@example.invalid")), authors)
        val finding = GuardFinding(GuardRule.SECRET, GuardSeverity.BLOCK, "a.kt", 1, "ghp_****", "GitHub token", "fp1")
        rig.useCase.allowAlways(COPY, finding, shared = true)
        assertEquals(listOf("fp1" to true), rig.guard.always)
    }

    @Test
    fun `a clean scan commits what is in the index`() = runTest {
        val rig = rig()
        val outcome = rig.useCase.commit(COPY, CommitRequest(draft)).getOrThrow()
        assertEquals(CommitOutcome.Committed(newId), outcome)
        assertEquals(listOf<GuardTarget>(GuardTarget.Staged), rig.guard.scans)
        assertEquals(listOf(Triple(draft, null, 3)), rig.committing.commits.toList())
    }

    @Test
    fun `a selection is scanned as the selection and committed as it`() = runTest {
        val rig = rig()
        val selection = ChangeSelection(listOf(FileSelection("a.kt", FileSelection.Scope.Hunks(setOf(1)))))
        rig.useCase.commit(COPY, CommitRequest(draft, selection, contextLines = 5)).getOrThrow()
        assertEquals(listOf<GuardTarget>(GuardTarget.Selection(selection, 5)), rig.guard.scans)
        assertEquals(listOf(Triple(draft, selection, 5)), rig.committing.commits.toList())
    }

    @Test
    fun `an unanswered finding stops the commit and nothing is written`() = runTest {
        val block = finding("aaa")
        val warn = finding("bbb", GuardSeverity.WARN)
        val rig = rig(GuardReport(listOf(block, warn)))
        val outcome = rig.useCase.commit(COPY, CommitRequest(draft)).getOrThrow()
        assertEquals(CommitOutcome.Stopped(GuardReport(listOf(block, warn))), outcome)
        assertTrue(rig.committing.commits.isEmpty())
        assertTrue(rig.guard.allowed.isEmpty())
    }

    @Test
    fun `findings the user allowed once are recorded, then the commit goes ahead`() = runTest {
        val rig = rig(GuardReport(listOf(finding("aaa"), finding("bbb", GuardSeverity.WARN))))
        val outcome = rig.useCase.commit(COPY, CommitRequest(draft, allowOnce = setOf("aaa", "bbb"))).getOrThrow()
        assertEquals(CommitOutcome.Committed(newId), outcome)
        assertEquals(listOf("aaa", "bbb"), rig.guard.allowed)
        assertEquals(1, rig.committing.commits.size)
    }

    @Test
    fun `answering some findings leaves the rest to stop it, and records none`() = runTest {
        val rig = rig(GuardReport(listOf(finding("aaa"), finding("bbb"))))
        val outcome =
            assertIs<CommitOutcome.Stopped>(
                rig.useCase.commit(COPY, CommitRequest(draft, allowOnce = setOf("aaa"))).getOrThrow(),
            )
        assertEquals(listOf("bbb"), outcome.report.findings.map { it.fingerprint })
        assertTrue(rig.guard.allowed.isEmpty())
        assertTrue(rig.committing.commits.isEmpty())
    }

    @Test
    fun `findings the allow-lists cover don't stop the commit and are not recorded`() = runTest {
        val rig = rig(GuardReport(findings = emptyList(), allowed = listOf(finding("aaa"))))
        assertEquals(CommitOutcome.Committed(newId), rig.useCase.commit(COPY, CommitRequest(draft)).getOrThrow())
        assertTrue(rig.guard.allowed.isEmpty())
    }

    @Test
    fun `with guards off nothing is scanned`() = runTest {
        val rig = rig(GuardReport(listOf(finding("aaa"))))
        assertEquals(
            CommitOutcome.Committed(newId),
            rig.useCase.commit(COPY, CommitRequest(draft, guards = false)).getOrThrow(),
        )
        assertTrue(rig.guard.scans.isEmpty())
    }

    @Test
    fun `the guard options go to the scan`() = runTest {
        val rig = rig()
        val options = GuardOptions(warnBytes = 1)
        rig.useCase.commit(COPY, CommitRequest(draft, guardOptions = options)).getOrThrow()
        assertEquals(listOf(options), rig.guard.options)
    }

    @Test
    fun `a scan that fails is a failure, and nothing is committed`() = runTest {
        val rig = rig()
        rig.guard.report = failure("GARD")
        assertIs<Result.Failure>(rig.useCase.commit(COPY, CommitRequest(draft)))
        assertTrue(rig.committing.commits.isEmpty())
    }

    @Test
    fun `if allowing once can't be recorded nothing is committed`() = runTest {
        val rig = rig(GuardReport(listOf(finding("aaa"))))
        rig.guard.allowFails = true
        assertIs<Result.Failure>(rig.useCase.commit(COPY, CommitRequest(draft, allowOnce = setOf("aaa"))))
        assertTrue(rig.committing.commits.isEmpty())
    }

    @Test
    fun `a commit that fails keeps the draft`() = runTest {
        val rig = rig(commit = failure("CMIT"))
        val key = DraftKey.Branch("refs/heads/main")
        rig.drafts.write(COPY, key, "Fix login").getOrThrow()
        assertIs<Result.Failure>(rig.useCase.commit(COPY, CommitRequest(draft, clearDraft = key)))
        assertEquals("Fix login", rig.drafts.read(COPY, key).getOrThrow())
    }

    @Test
    fun `a commit that succeeds forgets the draft`() = runTest {
        val rig = rig()
        val key = DraftKey.Branch("refs/heads/main")
        rig.drafts.write(COPY, key, "Fix login").getOrThrow()
        rig.useCase.commit(COPY, CommitRequest(draft, clearDraft = key)).getOrThrow()
        assertEquals("", rig.drafts.read(COPY, key).getOrThrow())
    }

    @Test
    fun `amending with the index scans the index and amends with it`() = runTest {
        val rig = rig()
        assertEquals(CommitOutcome.Committed(newId), rig.useCase.amend(COPY, CommitRequest(draft)).getOrThrow())
        assertEquals(listOf<GuardTarget>(GuardTarget.Staged), rig.guard.scans)
        assertEquals(listOf(draft to true), rig.committing.amends)
    }

    @Test
    fun `amending only the message has nothing to scan`() = runTest {
        val rig = rig(GuardReport(listOf(finding("aaa"))))
        assertEquals(
            CommitOutcome.Committed(newId),
            rig.useCase.amend(COPY, CommitRequest(draft), withStaged = false).getOrThrow(),
        )
        assertTrue(rig.guard.scans.isEmpty())
        assertEquals(listOf(draft to false), rig.committing.amends)
    }

    @Test
    fun `an amend with an unanswered finding is stopped too`() = runTest {
        val rig = rig(GuardReport(listOf(finding("aaa"))))
        assertIs<CommitOutcome.Stopped>(rig.useCase.amend(COPY, CommitRequest(draft)).getOrThrow())
        assertTrue(rig.committing.amends.isEmpty())
    }

    @Test
    fun `the previous message and the template come from the repository`() = runTest {
        val rig = rig()
        assertEquals(CommitMessageText("Old", "body"), rig.useCase.previousMessage(COPY).getOrThrow())
        assertNull(rig.useCase.template(COPY).getOrThrow())
    }

    // ---- what a list or section commits

    @Test
    fun `rows become a selection, whole files or the hunks a list holds`() {
        val rows =
            listOf(
                ChangeRow("a.kt", RowStatus.MODIFIED, hunks = setOf(0, 1), hunkCount = 2),
                ChangeRow("b.kt", RowStatus.MODIFIED, hunks = setOf(2), hunkCount = 3),
                ChangeRow("new.txt", RowStatus.UNTRACKED),
                ChangeRow("moved.kt", RowStatus.RENAMED, originalPath = "old.kt"),
            )
        assertEquals(
            ChangeSelection(
                listOf(
                    FileSelection("a.kt", FileSelection.Scope.WholeFile),
                    FileSelection("b.kt", FileSelection.Scope.Hunks(setOf(2))),
                    FileSelection("new.txt", FileSelection.Scope.WholeFile),
                    FileSelection("moved.kt", FileSelection.Scope.WholeFile, "old.kt"),
                ),
            ),
            rows.toSelection(),
        )
    }

    // ---- snapshots

    private class FakeHistory : LocalHistoryRepository by unsupported() {
        val taken = ArrayList<String?>()
        var result: Result<SnapshotResult>? = null

        override suspend fun snapshot(repository: Path, label: String?): Result<SnapshotResult> {
            taken += label
            return result ?: Result.Success(
                SnapshotResult(
                    Snapshot(ObjectId("1".repeat(40)), null, 0, label, ObjectId("2".repeat(40)), label.orEmpty()),
                    true,
                ),
            )
        }
    }

    @Test
    fun `snapshot now passes a trimmed label, and a blank one is no label`() = runTest {
        val history = FakeHistory()
        val snapshots = SnapshotUseCase(history)
        snapshots.snapshotNow(COPY, "  before refactor ").getOrThrow()
        snapshots.snapshotNow(COPY, "   ").getOrThrow()
        snapshots.snapshotNow(COPY).getOrThrow()
        assertEquals(listOf("before refactor", null, null), history.taken)
    }

    @Test
    fun `before a risky operation a labelled snapshot is taken, and a failure stops the operation`() = runTest {
        val history = FakeHistory()
        val before: BeforeRiskyOperation = SnapshotUseCase(history)
        before.beforeRisky(COPY, RiskyOperation.CHECKOUT).getOrThrow()
        before.beforeRisky(COPY, RiskyOperation.STASH_APPLY).getOrThrow()
        assertEquals(listOf("before checkout", "before stash apply"), history.taken.toList())
        history.result = failure("LHST")
        assertIs<Result.Failure>(before.beforeRisky(COPY, RiskyOperation.CLEAN))
    }

    @Test
    fun `every risky operation has its own label`() {
        assertEquals(RiskyOperation.entries.size, RiskyOperation.entries.map { it.label }.toSet().size)
        assertTrue(RiskyOperation.entries.all { it.label.isNotBlank() })
        assertEquals(ChangesMode.entries.size, 2)
    }
}
