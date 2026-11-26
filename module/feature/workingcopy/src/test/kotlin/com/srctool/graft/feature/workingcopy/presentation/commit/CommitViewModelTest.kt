// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.presentation.commit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.srctool.graft.data.git.api.models.CoAuthor
import com.srctool.graft.data.git.api.models.CommitMessageText
import com.srctool.graft.data.git.api.models.GuardFinding
import com.srctool.graft.data.git.api.models.GuardReport
import com.srctool.graft.data.git.api.models.GuardRule
import com.srctool.graft.data.git.api.models.GuardSeverity
import com.srctool.graft.data.git.api.models.Head
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.OperationEntry
import com.srctool.graft.feature.workingcopy.domain.COPY
import com.srctool.graft.feature.workingcopy.domain.api.models.ChangesModel
import com.srctool.graft.feature.workingcopy.domain.api.models.ChangesSection
import com.srctool.graft.feature.workingcopy.domain.api.models.SectionKind
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingCopy
import com.srctool.graft.feature.workingcopy.domain.api.usecases.CommitChangesUseCase
import com.srctool.graft.feature.workingcopy.domain.api.usecases.CommitOutcome
import com.srctool.graft.feature.workingcopy.domain.api.usecases.CommitRequest
import com.srctool.graft.feature.workingcopy.domain.api.usecases.DraftKey
import com.srctool.graft.feature.workingcopy.domain.api.usecases.DraftsUseCase
import com.srctool.graft.feature.workingcopy.domain.api.usecases.UndoRedoUseCase
import com.srctool.graft.feature.workingcopy.domain.failure
import com.srctool.graft.feature.workingcopy.domain.unsupported
import com.srctool.graft.feature.workingcopy.presentation.changes.FakeSession
import com.srctool.graft.feature.workingcopy.presentation.changes.FakeStaging
import com.srctool.graft.feature.workingcopy.presentation.changes.Sample
import com.srctool.graft.presentation.session.SnapshotPart
import com.srctool.sinew.models.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ID = ObjectId("c".repeat(40))

internal fun finding(
    path: String = "src/login/Config.kt",
    severity: GuardSeverity = GuardSeverity.BLOCK,
    fingerprint: String = "fp-$path",
    line: Int? = 14,
    rule: GuardRule = GuardRule.SECRET,
) = GuardFinding(rule, severity, path, line, "ghp_****…a91F", "GitHub token", fingerprint)

private class FakeCommits : CommitChangesUseCase {
    val requests = ArrayList<CommitRequest>()
    val amends = ArrayList<Pair<CommitRequest, Boolean>>()
    var outcome: Result<CommitOutcome> = Result.Success(CommitOutcome.Committed(ID))
    var previous: CommitMessageText? = CommitMessageText("Fix login timeout", "Raise it.")
    var template: String? = null
    val always = ArrayList<Pair<String, Boolean>>()
    var alwaysFails = false

    override suspend fun commit(workingCopy: WorkingCopy, request: CommitRequest): Result<CommitOutcome> {
        requests += request
        return outcome
    }

    override suspend fun amend(
        workingCopy: WorkingCopy,
        request: CommitRequest,
        withStaged: Boolean,
    ): Result<CommitOutcome> {
        amends += request to withStaged
        return outcome
    }

    override suspend fun previousMessage(workingCopy: WorkingCopy): Result<CommitMessageText?> =
        Result.Success(previous)

    override suspend fun template(workingCopy: WorkingCopy): Result<String?> = Result.Success(template)

    override suspend fun recentAuthors(workingCopy: WorkingCopy): Result<List<CoAuthor>> =
        Result.Success(listOf(CoAuthor("Ann Lee", "ann@example.invalid")))

    override suspend fun allowAlways(workingCopy: WorkingCopy, finding: GuardFinding, shared: Boolean): Result<Unit> {
        if (alwaysFails) return failure("GARD")
        always += finding.fingerprint to shared
        return Result.Success(Unit)
    }
}

/** Drafts in memory, by the branch's ref. */
private class FakeDrafts(val head: () -> String) : DraftsUseCase {
    val saved = HashMap<String, String>()

    override suspend fun key(
        workingCopy: WorkingCopy,
        mode: com.srctool.graft.data.app.api.models.ChangesMode,
    ): Result<DraftKey> = Result.Success(DraftKey.Branch("refs/heads/${head()}"))

    override suspend fun read(workingCopy: WorkingCopy, key: DraftKey): Result<String> =
        Result.Success(saved[(key as DraftKey.Branch).ref].orEmpty())

    override suspend fun write(workingCopy: WorkingCopy, key: DraftKey, text: String): Result<Unit> {
        if (text.isBlank()) saved.remove((key as DraftKey.Branch).ref) else saved[(key as DraftKey.Branch).ref] = text
        return Result.Success(Unit)
    }
}

private class FakeUndo : UndoRedoUseCase by unsupported() {
    var undone = 0
    var fails = false

    override suspend fun undo(workingCopy: WorkingCopy): Result<OperationEntry> {
        if (fails) return failure("UNDO")
        undone++
        val refs = com.srctool.graft.data.git.api.models.RefState(null, null, null)
        return Result.Success(
            OperationEntry(
                0,
                "main",
                com.srctool.graft.data.git.api.models.OperationKind.UNDO,
                listOf("git", "reset"),
                0,
                com.srctool.graft.data.git.api.models.OperationOutcome.SUCCEEDED,
                "",
                refs,
                refs,
            ),
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CommitViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val session = FakeSession()
    private var branch = "main"
    private val commits = FakeCommits()
    private val drafts = FakeDrafts { branch }
    private val staging = FakeStaging()
    private val undo = FakeUndo()
    private val lists = MutableStateFlow<ChangesModel?>(null)
    private val shown = ArrayList<String>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private inline fun <reified T : ViewModel> make(noinline create: () -> T): T =
        ViewModelProvider.create(store, viewModelFactory { initializer { create() } })[T::class]

    private fun staged(count: Int = 2, conflicts: Int = 0): ChangesModel {
        val rows = List(count) { Sample.login.copy(path = "f$it.kt") }
        val sections = buildList {
            if (conflicts >
                0
            ) {
                add(
                    ChangesSection(
                        SectionKind.Conflicts,
                        List(conflicts) {
                            Sample.session.copy(path = "c$it.kt")
                        },
                    ),
                )
            }
            if (count > 0) add(ChangesSection(SectionKind.Staged, rows))
        }
        return ChangesModel(COPY, sections)
    }

    private fun TestScope.opened(model: ChangesModel? = staged()): CommitViewModel {
        lists.value = model
        val vm =
            make {
                CommitViewModel(COPY, session, commits, drafts, staging, undo, lists, { shown += it })
            }
        vm.onEvent(CommitEvent.Opened)
        runCurrent()
        return vm
    }

    private fun CommitViewModel.type(summary: String, description: String = "") {
        onEvent(CommitEvent.SummaryChanged(summary))
        if (description.isNotEmpty()) onEvent(CommitEvent.DescriptionChanged(description))
    }

    @Test
    fun `commit is unavailable, with its reason, until there is a summary and something staged`() =
        runTest(dispatcher) {
            val vm = opened(model = null)
            assertEquals(Blocker.LOADING, vm.state.value.blocker)
            lists.value = staged(count = 0)
            runCurrent()
            assertEquals(Blocker.BLANK_SUMMARY, vm.state.value.blocker)
            vm.type("Fix")
            runCurrent()
            assertEquals(Blocker.NOTHING_STAGED, vm.state.value.blocker)
            vm.onEvent(CommitEvent.AllowEmptyToggled(true))
            runCurrent()
            assertNull(vm.state.value.blocker)
            vm.onEvent(CommitEvent.AllowEmptyToggled(false))
            lists.value = staged(count = 2, conflicts = 1)
            runCurrent()
            assertEquals(Blocker.CONFLICTS, vm.state.value.blocker)
            lists.value = staged(count = 2)
            runCurrent()
            assertNull(vm.state.value.blocker)
            assertEquals(2, vm.state.value.staged)
        }

    @Test
    fun `a summary is one line, and the guide counts it`() = runTest(dispatcher) {
        val vm = opened()
        vm.type("x".repeat(60) + "\nsecond")
        runCurrent()
        assertEquals("x".repeat(60), vm.state.value.summary)
        assertTrue(vm.state.value.guide.summaryTooLong)
    }

    @Test
    fun `the draft is saved a moment after typing, under its branch, and comes back`() = runTest(dispatcher) {
        val vm = opened()
        vm.type("Fix login", "Raise it.")
        runCurrent()
        assertTrue(drafts.saved.isEmpty(), "not saved on every key")
        advanceTimeBy(CommitViewModel.SAVE_MS + 1)
        assertEquals("Fix login\n\nRaise it.", drafts.saved["refs/heads/main"])

        val again = opened()
        assertEquals("Fix login", again.state.value.summary)
        assertEquals("Raise it.", again.state.value.description)
    }

    @Test
    fun `switching branch saves this draft and brings the other branch's back`() = runTest(dispatcher) {
        drafts.saved["refs/heads/feature"] = "Other work"
        val vm = opened()
        vm.type("Mine")
        runCurrent()
        branch = "feature"
        session.state.value =
            session.state.value.copy(summary = session.state.value.summary?.copy(head = Head.Branch("feature", null)))
        runCurrent()
        assertEquals("Mine", drafts.saved["refs/heads/main"])
        assertEquals("Other work", vm.state.value.summary)
    }

    @Test
    fun `an empty draft starts from the commit template`() = runTest(dispatcher) {
        commits.template = "Subject line\n\nExplain why."
        val vm = opened()
        assertEquals("Subject line", vm.state.value.summary)
        assertEquals("Explain why.", vm.state.value.description)
    }

    @Test
    fun `amend loads the last message, keeps what was typed, and gives it back`() = runTest(dispatcher) {
        val vm = opened()
        vm.type("Work in progress", "details")
        runCurrent()
        vm.onEvent(CommitEvent.AmendToggled(true))
        runCurrent()
        assertEquals("Fix login timeout", vm.state.value.summary)
        assertEquals("Raise it.", vm.state.value.description)
        vm.onEvent(CommitEvent.AmendToggled(false))
        runCurrent()
        assertEquals("Work in progress", vm.state.value.summary)
        assertEquals("details", vm.state.value.description)
    }

    @Test
    fun `amend is not offered before the first commit`() = runTest(dispatcher) {
        commits.previous = null
        val vm = opened()
        vm.onEvent(CommitEvent.AmendToggled(true))
        runCurrent()
        assertEquals(false, vm.state.value.amend)
        assertEquals(false, vm.state.value.canAmend)
    }

    @Test
    fun `an amend of the message alone needs nothing staged, and takes the index when there is some`() =
        runTest(dispatcher) {
            val vm = opened(model = staged(count = 0))
            vm.onEvent(CommitEvent.AmendToggled(true))
            vm.onEvent(CommitEvent.Submitted)
            runCurrent()
            assertEquals(false, commits.amends.single().second)
            val rich = opened(model = staged(count = 1))
            rich.onEvent(CommitEvent.AmendToggled(true))
            rich.onEvent(CommitEvent.Submitted)
            runCurrent()
            assertEquals(true, commits.amends.last().second)
        }

    @Test
    fun `a commit carries the options, empties the box, and offers undo`() = runTest(dispatcher) {
        val vm = opened()
        vm.type("Fix login", "Raise it.")
        vm.onEvent(CommitEvent.SignOffToggled(true))
        vm.onEvent(CommitEvent.NoVerifyToggled(true))
        vm.onEvent(CommitEvent.CoAuthorTyped("Bo Chen <bo@example.invalid>"))
        vm.onEvent(CommitEvent.CoAuthorAdded())
        runCurrent()
        vm.onEvent(CommitEvent.Submitted)
        runCurrent()
        val request = commits.requests.single()
        assertEquals("Fix login", request.draft.summary)
        assertEquals(true, request.draft.signOff)
        assertEquals(true, request.draft.noVerify)
        assertEquals(listOf(CoAuthor("Bo Chen", "bo@example.invalid")), request.draft.coAuthors)
        assertEquals(DraftKey.Branch("refs/heads/main"), request.clearDraft)
        val s = vm.state.value
        assertEquals("", s.summary)
        assertEquals(false, s.noVerify, "skip hooks is never kept for the next commit")
        assertTrue(s.coAuthors.isEmpty())
        assertEquals("Committed ccccccc", s.toast?.text)
        assertEquals(true, s.toast?.undo)
        assertTrue(session.refreshed.any { SnapshotPart.REFS in it })
    }

    @Test
    fun `a stopped commit opens the dialog and changes nothing in the box`() = runTest(dispatcher) {
        commits.outcome = Result.Success(CommitOutcome.Stopped(GuardReport(listOf(finding()))))
        val vm = opened()
        vm.type("Fix login", "details")
        vm.onEvent(CommitEvent.Submitted)
        runCurrent()
        val s = vm.state.value
        assertEquals(1, s.guard?.findings?.size)
        assertEquals("Fix login", s.summary)
        assertEquals("details", s.description)
        assertNull(s.busy)
        assertNull(s.toast)
    }

    @Test
    fun `commit anyway waits for every block to be answered, and then lets the answered ones through`() =
        runTest(dispatcher) {
            val block = finding("a.kt", fingerprint = "b1")
            val warn = finding("big.mp4", GuardSeverity.WARN, "w1", null, GuardRule.LARGE_FILE)
            commits.outcome = Result.Success(CommitOutcome.Stopped(GuardReport(listOf(block, warn))))
            val vm = opened()
            vm.type("Fix")
            vm.onEvent(CommitEvent.Submitted)
            runCurrent()
            assertEquals(false, vm.state.value.guard?.canProceed)
            vm.onEvent(CommitEvent.CommitAnyway)
            runCurrent()
            assertEquals(1, commits.requests.size, "nothing is sent while a block is unanswered")

            vm.onEvent(CommitEvent.AllowOnce("b1"))
            runCurrent()
            assertEquals(true, vm.state.value.guard?.canProceed)
            commits.outcome = Result.Success(CommitOutcome.Committed(ID))
            vm.onEvent(CommitEvent.CommitAnyway)
            runCurrent()
            assertEquals(setOf("b1", "w1"), commits.requests.last().allowOnce)
            assertNull(vm.state.value.guard)
            assertEquals("", vm.state.value.summary)
        }

    @Test
    fun `with only warnings, commit anyway is available at once`() = runTest(dispatcher) {
        commits.outcome =
            Result.Success(
                CommitOutcome.Stopped(GuardReport(listOf(finding("big.mp4", GuardSeverity.WARN, "w1", null)))),
            )
        val vm = opened()
        vm.type("Fix")
        vm.onEvent(CommitEvent.Submitted)
        runCurrent()
        assertEquals(true, vm.state.value.guard?.canProceed)
    }

    @Test
    fun `cancel closes the dialog and leaves the box and the index as they were`() = runTest(dispatcher) {
        commits.outcome = Result.Success(CommitOutcome.Stopped(GuardReport(listOf(finding()))))
        val vm = opened()
        vm.type("Fix", "d")
        vm.onEvent(CommitEvent.Submitted)
        runCurrent()
        vm.onEvent(CommitEvent.GuardCancelled)
        runCurrent()
        assertNull(vm.state.value.guard)
        assertEquals("Fix", vm.state.value.summary)
        assertTrue(staging.unstaged.isEmpty() && staging.staged.isEmpty())
    }

    @Test
    fun `unstaging a file takes its findings out, and the last one closes the dialog without committing`() =
        runTest(dispatcher) {
            commits.outcome =
                Result.Success(
                    CommitOutcome.Stopped(
                        GuardReport(listOf(finding("a.kt", fingerprint = "1"), finding("b.kt", fingerprint = "2"))),
                    ),
                )
            val vm = opened()
            vm.type("Fix")
            vm.onEvent(CommitEvent.Submitted)
            runCurrent()
            vm.onEvent(CommitEvent.UnstageFile("a.kt"))
            runCurrent()
            assertEquals(listOf("b.kt"), vm.state.value.guard?.findings?.map { it.path })
            assertEquals("a.kt", staging.unstaged.single().files.single().path)
            vm.onEvent(CommitEvent.UnstageFile("b.kt"))
            runCurrent()
            assertNull(vm.state.value.guard)
            assertEquals(1, commits.requests.size, "pressing Commit again is the user's call")
            assertNotNull(vm.state.value.toast)
        }

    @Test
    fun `allow always asks for you or the team, tells the guard, and drops the finding`() = runTest(dispatcher) {
        commits.outcome = Result.Success(CommitOutcome.Stopped(GuardReport(listOf(finding(fingerprint = "fp")))))
        val vm = opened()
        vm.type("Fix")
        vm.onEvent(CommitEvent.Submitted)
        runCurrent()
        vm.onEvent(CommitEvent.AllowAlwaysAsked("fp"))
        runCurrent()
        assertEquals("fp", vm.state.value.guard?.asking)
        vm.onEvent(CommitEvent.AllowAlways("fp", shared = true))
        runCurrent()
        assertEquals(listOf("fp" to true), commits.always)
        assertNull(vm.state.value.guard)
    }

    @Test
    fun `open at line closes the dialog and shows the file`() = runTest(dispatcher) {
        commits.outcome = Result.Success(CommitOutcome.Stopped(GuardReport(listOf(finding()))))
        val vm = opened()
        vm.type("Fix")
        vm.onEvent(CommitEvent.Submitted)
        runCurrent()
        vm.onEvent(CommitEvent.OpenAtLine("src/login/Config.kt"))
        runCurrent()
        assertNull(vm.state.value.guard)
        assertEquals(listOf("src/login/Config.kt"), shown)
    }

    @Test
    fun `a failed commit keeps the draft and shows the error`() = runTest(dispatcher) {
        commits.outcome = failure("COMM")
        val vm = opened()
        vm.type("Fix", "d")
        vm.onEvent(CommitEvent.Submitted)
        runCurrent()
        val s = vm.state.value
        assertNotNull(s.error)
        assertEquals("Fix", s.summary)
        assertNull(s.busy)
        vm.onEvent(CommitEvent.ErrorDismissed)
        runCurrent()
        assertNull(vm.state.value.error)
    }

    @Test
    fun `undo asks the operation log and says so`() = runTest(dispatcher) {
        val vm = opened()
        vm.type("Fix")
        vm.onEvent(CommitEvent.Submitted)
        runCurrent()
        vm.onEvent(CommitEvent.UndoClicked)
        runCurrent()
        assertEquals(1, undo.undone)
        assertEquals("Undone", vm.state.value.toast?.text)
        assertEquals(false, vm.state.value.toast?.undo)
    }

    @Test
    fun `the toast goes after 10 seconds`() = runTest(dispatcher) {
        val vm = opened()
        vm.type("Fix")
        vm.onEvent(CommitEvent.Submitted)
        runCurrent()
        assertNotNull(vm.state.value.toast)
        advanceTimeBy(CommitViewModel.TOAST_MS + 1)
        assertNull(vm.state.value.toast)
    }

    @Test
    fun `co-authors, a wrong line is flagged and a right one added once, a recent one picked, one removed`() =
        runTest(dispatcher) {
            val vm = opened()
            vm.onEvent(CommitEvent.CoAuthorTyped("nonsense"))
            vm.onEvent(CommitEvent.CoAuthorAdded())
            runCurrent()
            assertEquals(true, vm.state.value.coAuthorInvalid)
            vm.onEvent(CommitEvent.CoAuthorTyped("Bo Chen <bo@example.invalid>"))
            vm.onEvent(CommitEvent.CoAuthorAdded())
            vm.onEvent(CommitEvent.CoAuthorTyped("Bo Chen <bo@example.invalid>"))
            vm.onEvent(CommitEvent.CoAuthorAdded())
            vm.onEvent(CommitEvent.CoAuthorAdded(CoAuthor("Ann Lee", "ann@example.invalid")))
            runCurrent()
            assertEquals(2, vm.state.value.coAuthors.size)
            vm.onEvent(CommitEvent.CoAuthorRemoved(0))
            runCurrent()
            assertEquals(listOf("Ann Lee"), vm.state.value.coAuthors.map { it.name })
            assertEquals(listOf(CoAuthor("Ann Lee", "ann@example.invalid")), vm.state.value.recent)
        }
}
