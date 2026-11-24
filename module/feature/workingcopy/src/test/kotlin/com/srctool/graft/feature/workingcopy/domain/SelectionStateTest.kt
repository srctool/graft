// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain

import com.srctool.graft.data.git.api.models.ChangeSelection
import com.srctool.graft.data.git.api.models.FileSelection
import com.srctool.graft.data.git.api.models.FileSelection.Scope
import com.srctool.graft.data.git.api.models.LineRange
import com.srctool.graft.feature.workingcopy.domain.api.models.Check
import com.srctool.graft.feature.workingcopy.domain.api.models.FileInfo
import com.srctool.graft.feature.workingcopy.domain.api.models.HunkInfo
import com.srctool.graft.feature.workingcopy.domain.api.models.SelectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Selection arithmetic: whole files, hunks, lines and mixes, and the one form each selection normalizes to. */
class SelectionStateTest {
    // Three hunks. Lines count from 0 in each hunk; 0 and 4 are context in hunk 0, 3 is context in hunk 1.
    private val file =
        FileInfo(
            "src/a.kt",
            hunks =
            listOf(
                HunkInfo(0, setOf(1, 2, 3)),
                HunkInfo(1, setOf(0, 1, 2, 4, 5)),
                HunkInfo(2, setOf(2)),
            ),
        )
    private val binary = FileInfo("logo.png")
    private val renamed = FileInfo("new.kt", hunks = listOf(HunkInfo(0, setOf(1))), originalPath = "old.kt")

    private fun SelectionState.selection(vararg files: FileInfo): ChangeSelection =
        toSelection(files.associateBy { it.path })

    @Test
    fun `nothing is ticked at first`() {
        val state = SelectionState.EMPTY
        assertTrue(state.isEmpty)
        assertEquals(Check.NONE, state.check(file))
        assertEquals(ChangeSelection(emptyList()), state.selection(file))
    }

    @Test
    fun `ticking a file ticks it whole, and again clears it`() {
        val on = SelectionState.EMPTY.toggleFile(file)
        assertEquals(Check.ALL, on.check(file))
        assertEquals(Check.ALL, on.checkHunk(file, 1))
        assertTrue(on.isLineSelected(file, 1, 4))
        assertEquals(
            ChangeSelection(listOf(FileSelection("src/a.kt", Scope.WholeFile))),
            on.selection(file),
        )
        assertTrue(on.toggleFile(file).isEmpty)
    }

    @Test
    fun `a file with no hunks only moves whole, and hunk or line ticks are ignored`() {
        val state = SelectionState.EMPTY.toggleHunk(binary, 0).toggleLine(binary, 0, 1)
        assertTrue(state.isEmpty)
        assertEquals(Check.ALL, SelectionState.EMPTY.toggleFile(binary).check(binary))
    }

    @Test
    fun `some hunks make a partial file with a hunk selection`() {
        val state = SelectionState.EMPTY.toggleHunk(file, 0).toggleHunk(file, 2)
        assertEquals(Check.PARTIAL, state.check(file))
        assertEquals(Check.ALL, state.checkHunk(file, 0))
        assertEquals(Check.NONE, state.checkHunk(file, 1))
        assertEquals(
            ChangeSelection(listOf(FileSelection("src/a.kt", Scope.Hunks(setOf(0, 2))))),
            state.selection(file),
        )
    }

    @Test
    fun `every hunk ticked is the file ticked whole`() {
        val state = SelectionState.EMPTY.toggleHunk(file, 0).toggleHunk(file, 1).toggleHunk(file, 2)
        assertEquals(Check.ALL, state.check(file))
        assertEquals(Scope.WholeFile, state.selection(file).files.single().scope)
    }

    @Test
    fun `clearing one hunk of a whole file leaves the others`() {
        val state = SelectionState.EMPTY.toggleFile(file).toggleHunk(file, 1)
        assertEquals(Check.PARTIAL, state.check(file))
        assertEquals(Scope.Hunks(setOf(0, 2)), state.selection(file).files.single().scope)
        // Putting the last hunk back makes the file whole again.
        assertEquals(Check.ALL, state.toggleHunk(file, 1).check(file))
    }

    @Test
    fun `clearing the last hunk clears the file`() {
        val state = SelectionState.EMPTY.toggleHunk(file, 1).toggleHunk(file, 1)
        assertTrue(state.isEmpty)
    }

    @Test
    fun `lines tick within a hunk and a hunk with only some lines is partial`() {
        val state = SelectionState.EMPTY.toggleLine(file, 0, 1).toggleLine(file, 0, 3)
        assertEquals(Check.PARTIAL, state.check(file))
        assertEquals(Check.PARTIAL, state.checkHunk(file, 0))
        assertTrue(state.isLineSelected(file, 0, 1))
        assertTrue(!state.isLineSelected(file, 0, 2))
        // Line 2 is changed and not ticked, so the ticks are two ranges.
        assertEquals(
            ChangeSelection(
                listOf(FileSelection("src/a.kt", Scope.Lines(listOf(LineRange(0, 1, 1), LineRange(0, 3, 3))))),
            ),
            state.selection(file),
        )
    }

    @Test
    fun `context between ticked lines doesn't split a range`() {
        // Hunk 1: change lines 0,1,2 then context at 3, then 4,5. Ticking 2 and 4 spans the context line.
        val state = SelectionState.EMPTY.toggleLine(file, 1, 2).toggleLine(file, 1, 4)
        assertEquals(
            Scope.Lines(listOf(LineRange(1, 2, 4))),
            state.selection(file).files.single().scope,
        )
    }

    @Test
    fun `every changed line of a hunk ticked is the hunk ticked`() {
        val state = SelectionState.EMPTY.toggleLine(file, 2, 2)
        assertEquals(Check.PARTIAL, state.check(file))
        assertEquals(Check.ALL, state.checkHunk(file, 2))
        assertEquals(Scope.Hunks(setOf(2)), state.selection(file).files.single().scope)
    }

    @Test
    fun `a context line can't be ticked`() {
        val state = SelectionState.EMPTY.toggleLine(file, 0, 0).toggleLine(file, 1, 3)
        assertTrue(state.isEmpty)
    }

    @Test
    fun `clearing a line of a ticked hunk leaves the hunk's other lines`() {
        val state = SelectionState.EMPTY.toggleHunk(file, 0).toggleLine(file, 0, 2)
        assertEquals(Check.PARTIAL, state.checkHunk(file, 0))
        assertEquals(
            Scope.Lines(listOf(LineRange(0, 1, 1), LineRange(0, 3, 3))),
            state.selection(file).files.single().scope,
        )
    }

    @Test
    fun `clearing a line of a whole file leaves everything else`() {
        val state = SelectionState.EMPTY.toggleFile(file).toggleLine(file, 1, 4)
        assertEquals(Check.PARTIAL, state.check(file))
        assertEquals(Check.PARTIAL, state.checkHunk(file, 1))
        assertEquals(Check.ALL, state.checkHunk(file, 2))
        assertEquals(
            Scope.Lines(
                listOf(LineRange(0, 1, 3), LineRange(1, 0, 2), LineRange(1, 5, 5), LineRange(2, 2, 2)),
            ),
            state.selection(file).files.single().scope,
        )
    }

    @Test
    fun `whole hunks and some lines of others are one line selection`() {
        val state = SelectionState.EMPTY.toggleHunk(file, 0).toggleLine(file, 1, 1)
        assertEquals(
            Scope.Lines(listOf(LineRange(0, 1, 3), LineRange(1, 1, 1))),
            state.selection(file).files.single().scope,
        )
    }

    @Test
    fun `a partly ticked hunk is ticked whole by a hunk click`() {
        val state = SelectionState.EMPTY.toggleLine(file, 0, 1).toggleHunk(file, 0)
        assertEquals(Check.ALL, state.checkHunk(file, 0))
        assertEquals(Scope.Hunks(setOf(0)), state.selection(file).files.single().scope)
    }

    @Test
    fun `a partly ticked file is ticked whole by a file click, and a whole one is cleared`() {
        val partial = SelectionState.EMPTY.toggleHunk(file, 0)
        assertEquals(Check.ALL, partial.toggleFile(file).check(file))
        assertTrue(partial.toggleFile(file).toggleFile(file).isEmpty)
    }

    @Test
    fun `the selection lists files by path and carries a rename's old path`() {
        val state = SelectionState.EMPTY.toggleFile(renamed).toggleFile(file).toggleFile(binary)
        val files = state.selection(file, renamed, binary).files
        assertEquals(listOf("logo.png", "new.kt", "src/a.kt"), files.map { it.path })
        assertEquals("old.kt", files.single { it.path == "new.kt" }.originalPath)
    }

    @Test
    fun `a path with no file info is taken as a whole file`() {
        val state = SelectionState.EMPTY.toggleFile(file)
        assertEquals(
            ChangeSelection(listOf(FileSelection("src/a.kt", Scope.WholeFile))),
            state.toSelection(emptyMap()),
        )
    }

    @Test
    fun `retain drops paths that left the panel`() {
        val state = SelectionState.EMPTY.toggleFile(file).toggleFile(binary)
        assertEquals(setOf("logo.png"), state.retain(setOf("logo.png", "other")).paths)
        assertEquals(state, state.retain(setOf("logo.png", "src/a.kt")))
    }

    @Test
    fun `states are values and never change`() {
        val a = SelectionState.EMPTY.toggleHunk(file, 0)
        val b = a.toggleHunk(file, 1)
        assertEquals(setOf("src/a.kt"), a.paths)
        assertEquals(Check.NONE, a.checkHunk(file, 1))
        assertEquals(Check.ALL, b.checkHunk(file, 1))
        assertEquals(a, SelectionState.EMPTY.toggleHunk(file, 0))
    }

    @Test
    fun `a hunk that the diff no longer has is dropped when the file is re-read`() {
        val before = SelectionState.EMPTY.toggleHunk(file, 2).toggleHunk(file, 0)
        val shorter = file.copy(hunks = file.hunks.take(2))
        // Toggling anything normalizes against the new diff: hunk 2 is gone, hunk 0 stays.
        val after = before.toggleHunk(shorter, 1).toggleHunk(shorter, 1)
        assertEquals(Scope.Hunks(setOf(0)), after.selection(shorter).files.single().scope)
    }
}
