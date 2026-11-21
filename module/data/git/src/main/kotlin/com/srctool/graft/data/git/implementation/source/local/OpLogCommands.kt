// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.source.local

import com.srctool.graft.core.git.GitCommand
import com.srctool.graft.data.git.api.models.ObjectId

/** The full name of the checked-out branch, or null when HEAD is detached (`symbolic-ref` exits 1 then). */
internal class HeadBranchCommand : GitCommand<String?>() {
    override val function = "HBRN"
    override val args = listOf("symbolic-ref", "--quiet", "HEAD")
    override val successExitCodes = setOf(0, 1)

    override fun parse(stdout: ByteArray): String? = GitText.decode(stdout).trim().takeIf { it.isNotEmpty() }
}

/**
 * Moves [ref] to [new] only if it is still [old] (null: only if it does not exist), with [reason] in the reflog. With
 * a null [new] the ref is deleted instead. `--no-deref` so that `HEAD` itself moves when it is detached.
 */
internal class MoveRefCommand(ref: String, new: ObjectId?, old: ObjectId?, reason: String) : GitCommand<Unit>() {
    override val function = "MREF"
    override val mutates = true
    override val args =
        if (new == null) {
            listOf("update-ref", "-m", reason, "-d", "--no-deref", ref, old?.hex.orEmpty())
        } else {
            listOf("update-ref", "-m", reason, "--no-deref", ref, new.hex, old?.hex.orEmpty())
        }

    override fun parse(stdout: ByteArray) = Unit
}
