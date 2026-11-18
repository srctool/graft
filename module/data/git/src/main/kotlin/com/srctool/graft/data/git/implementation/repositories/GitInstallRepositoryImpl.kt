// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.repositories

import com.srctool.graft.core.git.GitException
import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.core.git.GitVersionException
import com.srctool.graft.core.git.checkVersion
import com.srctool.graft.data.git.api.models.GitInstall
import com.srctool.graft.data.git.api.models.GitInstallStatus
import com.srctool.graft.data.git.api.repositories.GitInstallRepository
import com.srctool.sinew.models.Result
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Runs git in [neutralFolder], like the identity: no repository's config applies. */
internal class GitInstallRepositoryImpl(
    private val runner: GitRunner,
    private val neutralFolder: Path,
    private val searchPath: () -> String? = { System.getenv("PATH") },
) : GitInstallRepository {
    override suspend fun check(): GitInstallStatus = when (val r = runner.checkVersion(neutralFolder)) {
        is Result.Success -> GitInstallStatus.Ready(GitInstall(r.data.toString(), onPath()))

        is Result.Failure -> {
            val error = r.error
            when {
                error is GitVersionException -> GitInstallStatus.TooOld(error.found, error.required)

                // The code ends in the failing kind: GIT-R-VERS-NF is git that couldn't be started.
                (error as? GitException)?.code == NOT_FOUND -> GitInstallStatus.Missing

                else -> GitInstallStatus.Unreadable(error)
            }
        }
    }

    private fun onPath(): Path? {
        val names = listOf("git", "git.exe")
        return searchPath()?.split(File.pathSeparator).orEmpty().asSequence()
            .filter { it.isNotBlank() }
            .flatMap { dir -> names.asSequence().map { runCatching { Paths.get(dir, it) }.getOrNull() } }
            .firstOrNull { it != null && Files.isRegularFile(it) && Files.isExecutable(it) }
    }

    private companion object {
        const val NOT_FOUND = "GIT-R-VERS-NF"
    }
}
