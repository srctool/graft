// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.presentation.home

import java.nio.file.Path

internal sealed interface HomeEvent {
    data object Opened : HomeEvent

    data object Retry : HomeEvent

    data class ViewChosen(val view: HomeView) : HomeEvent

    data class QueryTyped(val query: String) : HomeEvent

    /** A row came into view: its summary is read (four at a time). */
    data class RowShown(val path: Path) : HomeEvent

    data class Focused(val key: String) : HomeEvent

    data class Moved(val by: Int) : HomeEvent

    /** Open a known repository, or a folder from the picker or a drop: git finds the repository it belongs to. */
    data class OpenFolder(val folder: Path) : HomeEvent

    data object OpenFocused : HomeEvent

    data object OpenAll : HomeEvent

    data class PinToggled(val path: Path) : HomeEvent

    data object PinFocused : HomeEvent

    /** Delete on a row, or **Remove** on a missing one: asks first. */
    data class RemoveAsked(val path: Path) : HomeEvent

    data object RemoveFocusedAsked : HomeEvent

    data object RemoveConfirmed : HomeEvent

    /** **Locate…**: the folder the repository moved to. */
    data class Located(val from: Path, val to: Path) : HomeEvent

    /** A dialog opens, or its fields change (a pure state change). */
    data class DialogSet(val dialog: HomeDialog) : HomeEvent

    data object DialogClosed : HomeEvent

    data object TrustConfirmed : HomeEvent

    data class ProblemOutputShown(val shown: Boolean) : HomeEvent

    data class ScanFolderChosen(val folder: Path) : HomeEvent

    data class ScanToggled(val path: Path) : HomeEvent

    data object ScanAllToggled : HomeEvent

    data object ScanConfirmed : HomeEvent

    data object NewWorkspaceConfirmed : HomeEvent

    data object RenameConfirmed : HomeEvent

    data object DeleteWorkspaceConfirmed : HomeEvent

    data class AddToWorkspaceToggled(val path: Path) : HomeEvent

    /** Known repositories picked in the dialog, or a folder from its picker, join the workspace. */
    data object AddToWorkspaceConfirmed : HomeEvent

    data class FolderAddedToWorkspace(val id: String, val folder: Path) : HomeEvent

    data class WorkspaceRepositoryRemoved(val id: String, val path: Path) : HomeEvent

    /** Clone or Init finished: the repository opens, and the list reloads. */
    data class Created(val root: Path) : HomeEvent
}

internal sealed interface HomeAction {
    data object Start : HomeAction

    class Local(val change: (HomeState) -> HomeState) : HomeAction

    data class Summarize(val path: Path) : HomeAction

    data class Open(val folder: Path) : HomeAction

    data object OpenFocused : HomeAction

    data object OpenAll : HomeAction

    data class Pin(val path: Path?) : HomeAction

    data class AskRemove(val path: Path?) : HomeAction

    data object ConfirmRemove : HomeAction

    data class Locate(val from: Path, val to: Path) : HomeAction

    data object Trust : HomeAction

    data class Scan(val folder: Path) : HomeAction

    data object AddScanned : HomeAction

    data object CreateWorkspace : HomeAction

    data object RenameWorkspace : HomeAction

    data object DeleteWorkspace : HomeAction

    data object AddToWorkspace : HomeAction

    data class AddFolderToWorkspace(val id: String, val folder: Path) : HomeAction

    data class RemoveFromWorkspace(val id: String, val path: Path) : HomeAction

    data class Created(val root: Path) : HomeAction

    data object Reload : HomeAction
}

internal sealed interface HomeEffect {
    /** Open the repository at [root] (the working tree's top, or a bare repository's git folder). */
    data class Open(val root: Path, val subfolder: Path? = null) : HomeEffect

    /** Open every one of [roots], each in its own tab (the temporary host opens the first until Task 20). */
    data class OpenAll(val roots: List<Path>) : HomeEffect
}
