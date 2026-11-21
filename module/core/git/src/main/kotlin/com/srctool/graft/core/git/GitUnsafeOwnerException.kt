// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.graft.core.exception.GraftCodes.Types
import com.srctool.sinew.models.ErrorMessage

/**
 * Git refused the repository because another user owns it (`safe.directory`, Git's fix for CVE-2022-24765).
 * [repositoryPath] is the folder git named, for the **Trust this folder** offer; empty if git didn't say.
 */
public class GitUnsafeOwnerException(function: String, argv: List<String>, exitCode: Int?, stderr: String) :
    GitException(function, argv, exitCode, stderr) {
    override val typeCode: String = Types.UNSAFE_OWNER
    override val errorMessage: ErrorMessage =
        local("unsafeOwner", "Git doesn't trust this folder because another user owns it.")

    public val repositoryPath: String =
        (newWording.find(this.stderr) ?: oldWording.find(this.stderr))?.groupValues?.get(1).orEmpty()

    private companion object {
        val newWording = Regex("""dubious ownership in repository at '([^'\n]*)'""")
        val oldWording = Regex("""unsafe repository \('([^'\n]*)' is owned""")
    }
}
