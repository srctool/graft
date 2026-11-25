// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.presentation.changes

import com.srctool.graft.data.app.api.models.ChangesMode
import com.srctool.graft.data.app.api.models.ChangesPanelState
import com.srctool.graft.data.app.api.models.ChangesView
import com.srctool.graft.data.app.api.models.RepositoryUiState
import com.srctool.graft.data.app.api.models.Saved
import com.srctool.graft.data.app.api.models.SavedStatus
import com.srctool.graft.data.app.api.repositories.RepositoryUiStateRepository
import com.srctool.graft.data.git.api.models.ChangeSelection
import com.srctool.graft.data.git.api.models.ChangedFile
import com.srctool.graft.data.git.api.models.DiffLine
import com.srctool.graft.data.git.api.models.FileDiff
import com.srctool.graft.data.git.api.models.FileStatus
import com.srctool.graft.data.git.api.models.Head
import com.srctool.graft.data.git.api.models.Hunk
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.data.git.api.models.RepositorySummary
import com.srctool.graft.feature.workingcopy.domain.COPY
import com.srctool.graft.feature.workingcopy.domain.LOCATION
import com.srctool.graft.feature.workingcopy.domain.api.models.ChangeRow
import com.srctool.graft.feature.workingcopy.domain.api.models.ChangesModel
import com.srctool.graft.feature.workingcopy.domain.api.models.ChangesSection
import com.srctool.graft.feature.workingcopy.domain.api.models.DiffSide
import com.srctool.graft.feature.workingcopy.domain.api.models.DiscardRecord
import com.srctool.graft.feature.workingcopy.domain.api.models.FileInfo
import com.srctool.graft.feature.workingcopy.domain.api.models.HunkInfo
import com.srctool.graft.feature.workingcopy.domain.api.models.RowStatus
import com.srctool.graft.feature.workingcopy.domain.api.models.SectionKind
import com.srctool.graft.feature.workingcopy.domain.api.models.UndoDiscardOutcome
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingCopy
import com.srctool.graft.feature.workingcopy.domain.api.models.WorkingDiff
import com.srctool.graft.feature.workingcopy.domain.api.usecases.ChangesLoad
import com.srctool.graft.feature.workingcopy.domain.api.usecases.LoadChangesUseCase
import com.srctool.graft.feature.workingcopy.domain.api.usecases.PanelStateUseCase
import com.srctool.graft.feature.workingcopy.domain.api.usecases.StageChangesUseCase
import com.srctool.graft.feature.workingcopy.domain.api.usecases.WorkingDiffUseCase
import com.srctool.graft.feature.workingcopy.domain.unsupported
import com.srctool.graft.presentation.session.RepoSnapshot
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.session.SnapshotPart
import com.srctool.sinew.models.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/** Rows and models for the panel's tests: a staged file, two unstaged ones (one also staged), an untracked one. */
internal object Sample {
    val login = ChangeRow("src/login/Login.kt", RowStatus.MODIFIED)
    val backoff = ChangeRow("src/login/Backoff.kt", RowStatus.ADDED)
    val session = ChangeRow("src/login/Session.kt", RowStatus.MODIFIED)
    val notes = ChangeRow("notes.txt", RowStatus.UNTRACKED)
    val engine =
        ChangeRow(
            "libs/engine",
            RowStatus.MODIFIED,
            submodule = com.srctool.graft.data.git.api.models.SubmoduleChange(true, true, false),
        )

    fun model(): ChangesModel = ChangesModel(
        COPY,
        listOf(
            ChangesSection(SectionKind.Staged, listOf(login, backoff)),
            ChangesSection(SectionKind.Unstaged, listOf(login, session, notes, engine)),
        ),
    )

    /** One hunk with a context line, two removed and two added lines: positions 1–4 can be ticked. */
    fun diff(path: String = login.path): WorkingDiff.Text {
        val lines =
            listOf(
                DiffLine(DiffLine.Kind.CONTEXT, 10, 10, "val t = 30"),
                DiffLine(DiffLine.Kind.REMOVED, 11, null, "val timeout = 30"),
                DiffLine(DiffLine.Kind.REMOVED, 12, null, "val retries = 1"),
                DiffLine(DiffLine.Kind.ADDED, null, 11, "val timeout = 60"),
                DiffLine(DiffLine.Kind.ADDED, null, 12, "val retries = 3"),
                DiffLine(DiffLine.Kind.CONTEXT, 13, 13, "return connect(timeout)"),
            )
        val file = ChangedFile(FileStatus.MODIFIED, path, null, null, "100644", "100644", 2, 2)
        val hunk = Hunk(10, 4, 10, 4, "fun login()", lines)
        return WorkingDiff.Text(FileDiff(file, listOf(hunk)), FileInfo(path, listOf(HunkInfo(0, setOf(1, 2, 3, 4)))))
    }
}

internal class FakeSession(head: Head = Head.Branch("main", null)) : RepositorySession {
    val state =
        MutableStateFlow(
            RepoSnapshot(LOCATION, summary = RepositorySummary(head, null, dirty = true, changedFiles = 4)),
        )
    val refreshed = ArrayList<Set<SnapshotPart>>()
    override val location: RepositoryLocation = LOCATION
    override val snapshot: StateFlow<RepoSnapshot> = state

    override fun refresh(parts: Set<SnapshotPart>) {
        refreshed += parts
    }

    override suspend fun <T> write(block: suspend () -> T): T = block()
}

internal class FakeUiState : RepositoryUiStateRepository {
    override suspend fun read(commonDir: Path): Result<Saved<RepositoryUiState>> =
        Result.Success(Saved(RepositoryUiState(), SavedStatus.Current))

    override suspend fun update(
        commonDir: Path,
        change: (RepositoryUiState) -> RepositoryUiState,
    ): Result<RepositoryUiState> = Result.Success(change(RepositoryUiState()))
}

/** Emits what a test pushes into [loads], as the status refreshes would. */
internal class FakeLoads : LoadChangesUseCase {
    val loads = MutableSharedFlow<Result<ChangesLoad>>(replay = 1)

    override suspend fun load(workingCopy: WorkingCopy, mode: ChangesMode): Result<ChangesLoad> =
        loads.replayCache.first()

    override fun observe(
        workingCopy: WorkingCopy,
        mode: ChangesMode,
        refreshes: Flow<Unit>,
    ): Flow<Result<ChangesLoad>> = loads

    fun push(model: ChangesModel) {
        loads.tryEmit(Result.Success(ChangesLoad(model, null, emptyList())))
    }
}

internal class FakePanelState : PanelStateUseCase by unsupported() {
    var tree = true
    var collapsed = emptySet<String>()

    override suspend fun read(workingCopy: WorkingCopy): Result<ChangesPanelState> =
        Result.Success(ChangesPanelState(tree, collapsed))

    override suspend fun setTree(workingCopy: WorkingCopy, tree: Boolean): Result<ChangesPanelState> {
        this.tree = tree
        return read(workingCopy)
    }

    override suspend fun toggleFolder(workingCopy: WorkingCopy, path: String): Result<ChangesPanelState> {
        collapsed = if (path in collapsed) collapsed - path else collapsed + path
        return read(workingCopy)
    }

    override suspend fun mode(workingCopy: WorkingCopy, app: ChangesMode): Result<ChangesMode> = Result.Success(app)

    override suspend fun setView(workingCopy: WorkingCopy, view: ChangesView): Result<Unit> = Result.Success(Unit)
}

internal class FakeWorkingDiffs(var diff: Result<WorkingDiff> = Result.Success(Sample.diff())) : WorkingDiffUseCase {
    val reads = ArrayList<Pair<String, DiffSide>>()

    override suspend fun read(
        workingCopy: WorkingCopy,
        row: ChangeRow,
        side: DiffSide,
        anyway: Boolean,
        limit: Long,
    ): Result<WorkingDiff> {
        reads += row.path to side
        return diff
    }
}

/** Records what the panel asked for; [fail] makes the next write fail. */
internal class FakeStaging : StageChangesUseCase {
    val staged = ArrayList<ChangeSelection>()
    val unstaged = ArrayList<ChangeSelection>()
    val discarded = ArrayList<ChangeSelection>()
    var undone = 0
    var failure: Result.Failure? = null
    var outcome: UndoDiscardOutcome = UndoDiscardOutcome.Restored

    override suspend fun stage(workingCopy: WorkingCopy, selection: ChangeSelection): Result<Unit> =
        failure ?: Result.Success(Unit).also { staged += selection }

    override suspend fun unstage(workingCopy: WorkingCopy, selection: ChangeSelection): Result<Unit> =
        failure ?: Result.Success(Unit).also { unstaged += selection }

    override suspend fun discard(workingCopy: WorkingCopy, selection: ChangeSelection): Result<DiscardRecord> {
        failure?.let { return it }
        discarded += selection
        return Result.Success(DiscardRecord(ObjectId("a".repeat(40)), selection.files.associate { it.path to "x" }))
    }

    override suspend fun undoDiscard(workingCopy: WorkingCopy, record: DiscardRecord): Result<UndoDiscardOutcome> {
        undone++
        return Result.Success(outcome)
    }
}
