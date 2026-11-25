// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.presentation.commit

import com.srctool.graft.data.git.api.models.CoAuthor
import com.srctool.graft.data.git.api.models.CommitGuide
import com.srctool.graft.data.git.api.models.CommitMessageText
import com.srctool.graft.data.git.api.models.GuardFinding
import com.srctool.graft.data.git.api.models.GuardSeverity

/** Why Commit can't be pressed now; the box says it as a tooltip and in text for screen readers. */
internal enum class Blocker { LOADING, BLANK_SUMMARY, NOTHING_STAGED, CONFLICTS, BUSY }

/**
 * The guard dialog (S10): [findings] still need an answer, [answered] are the BLOCK fingerprints the user allowed once.
 * A finding unstaged or allowed always leaves the list. [asking] is the finding whose "Allow always…" question is
 * open.
 */
internal data class GuardDialog(
    val findings: List<GuardFinding>,
    val answered: Set<String> = emptySet(),
    val asking: String? = null,
    val amend: Boolean = false,
) {
    /** Every BLOCK finding has an answer, so "Commit anyway" works; warnings never need one. */
    val canProceed: Boolean
        get() = findings.filter { it.severity == GuardSeverity.BLOCK }.all { it.fingerprint in answered }
}

internal data class CommitToast(val text: String, val undo: Boolean = false, val stamp: Long = 0)

/** The commit box under the Changes lists (S9, Task 13 wireframes). */
internal data class CommitState(
    val summary: String = "",
    val description: String = "",
    val amend: Boolean = false,
    /** The last commit's message and short id, null before the first commit: what Amend loads. */
    val previous: CommitMessageText? = null,
    val previousId: String = "",
    val signOff: Boolean = false,
    val noVerify: Boolean = false,
    val allowEmpty: Boolean = false,
    val coAuthors: List<CoAuthor> = emptyList(),
    val coAuthorInput: String = "",
    val coAuthorInvalid: Boolean = false,
    val recent: List<CoAuthor> = emptyList(),
    val optionsOpen: Boolean = false,
    /** The lists were read, so the counts below mean something. */
    val loaded: Boolean = false,
    val staged: Int = 0,
    val conflicts: Int = 0,
    val busy: String? = null,
    val error: Throwable? = null,
    val outputShown: Boolean = false,
    val guard: GuardDialog? = null,
    val toast: CommitToast? = null,
) {
    val guide: CommitGuide get() = CommitGuide.of(summary, description)

    val optionsOn: Boolean get() = signOff || noVerify || allowEmpty || coAuthors.isNotEmpty()

    val blocker: Blocker?
        get() = when {
            busy != null -> Blocker.BUSY

            !loaded -> Blocker.LOADING

            summary.isBlank() -> Blocker.BLANK_SUMMARY

            conflicts > 0 -> Blocker.CONFLICTS

            // An amend with nothing staged changes only the message; a normal commit needs something, or the option.
            !amend && staged == 0 && !allowEmpty -> Blocker.NOTHING_STAGED

            else -> null
        }

    val canAmend: Boolean get() = previous != null
}

internal sealed interface CommitEvent {
    data object Opened : CommitEvent

    data class SummaryChanged(val text: String) : CommitEvent

    data class DescriptionChanged(val text: String) : CommitEvent

    data class AmendToggled(val on: Boolean) : CommitEvent

    data class SignOffToggled(val on: Boolean) : CommitEvent

    data class NoVerifyToggled(val on: Boolean) : CommitEvent

    data class AllowEmptyToggled(val on: Boolean) : CommitEvent

    data class OptionsShown(val open: Boolean) : CommitEvent

    data class CoAuthorTyped(val text: String) : CommitEvent

    /** Enter in the co-author field, or a recent author picked. */
    data class CoAuthorAdded(val author: CoAuthor? = null) : CommitEvent

    data class CoAuthorRemoved(val index: Int) : CommitEvent

    /** The Commit button, or Mod+Enter in a field. */
    data object Submitted : CommitEvent

    data class AllowOnce(val fingerprint: String) : CommitEvent

    data class AllowAlwaysAsked(val fingerprint: String?) : CommitEvent

    data class AllowAlways(val fingerprint: String, val shared: Boolean) : CommitEvent

    data class UnstageFile(val path: String) : CommitEvent

    data class OpenAtLine(val path: String) : CommitEvent

    data object CommitAnyway : CommitEvent

    data object GuardCancelled : CommitEvent

    data object UndoClicked : CommitEvent

    data object ToastDismissed : CommitEvent

    data object ErrorDismissed : CommitEvent

    data class OutputShown(val shown: Boolean) : CommitEvent
}

internal sealed interface CommitAction {
    data object Start : CommitAction

    class Local(val change: (CommitState) -> CommitState) : CommitAction

    data class Edited(val change: (CommitState) -> CommitState) : CommitAction

    data class Amend(val on: Boolean) : CommitAction

    data class AddAuthor(val author: CoAuthor?) : CommitAction

    data object Submit : CommitAction

    data class Allow(val fingerprint: String) : CommitAction

    data class Always(val fingerprint: String, val shared: Boolean) : CommitAction

    data class Unstage(val path: String) : CommitAction

    data class Show(val path: String) : CommitAction

    data object Anyway : CommitAction

    data object Undo : CommitAction
}

internal sealed interface CommitEffect {
    /** Mod+K and the amend key move the cursor to the summary. */
    data object FocusSummary : CommitEffect
}
