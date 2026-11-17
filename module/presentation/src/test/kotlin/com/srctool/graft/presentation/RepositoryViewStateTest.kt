// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.presentation

import com.srctool.graft.data.app.api.models.RepositoryUiState
import com.srctool.graft.data.app.api.models.Saved
import com.srctool.graft.data.app.api.models.SavedStatus
import com.srctool.graft.data.app.api.repositories.RepositoryUiStateRepository
import com.srctool.graft.presentation.view.RepositoryViewState
import com.srctool.sinew.models.Result
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class RepositoryViewStateTest {
    private class Store(var value: RepositoryUiState) : RepositoryUiStateRepository {
        var reads = 0

        override suspend fun read(commonDir: Path): Result<Saved<RepositoryUiState>> {
            reads++
            return Result.Success(Saved(value, SavedStatus.Current))
        }

        override suspend fun update(
            commonDir: Path,
            change: (RepositoryUiState) -> RepositoryUiState,
        ): Result<RepositoryUiState> {
            value = change(value)
            return Result.Success(value)
        }
    }

    @Test
    fun `the saved choices load once, and updates reach every reader and the file`() = runTest {
        val store = Store(RepositoryUiState(pinnedRefs = setOf("refs/heads/main")))
        val view = RepositoryViewState(Paths.get("/repo/.git"), store)
        view.load()
        view.load()
        assertEquals(1, store.reads)
        assertEquals(setOf("refs/heads/main"), view.ui.value.pinnedRefs)

        view.update { it.copy(hiddenRefs = it.hiddenRefs + "refs/heads/old") }
        view.update { it.copy(soloRefs = setOf("refs/heads/main")) }
        val expected =
            RepositoryUiState(
                hiddenRefs = setOf("refs/heads/old"),
                soloRefs = setOf("refs/heads/main"),
                pinnedRefs = setOf("refs/heads/main"),
            )
        assertEquals(expected, view.ui.value)
        assertEquals(expected, store.value)
    }
}
