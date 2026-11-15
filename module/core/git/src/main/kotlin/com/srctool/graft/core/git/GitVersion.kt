// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.graft.core.exception.GitVersionException
import com.srctool.sinew.models.Result
import java.nio.file.Path

/** A Git release, as `git --version` reports it. */
public data class GitVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<GitVersion> {
    override fun compareTo(other: GitVersion): Int =
        compareValuesBy(this, other, GitVersion::major, GitVersion::minor, GitVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    public companion object {
        /** Porcelain worktree listing with `-z` and `merge-tree --write-tree` need it (Git Engine note). */
        public val MINIMUM: GitVersion = GitVersion(2, 38, 0)

        private val pattern = Regex("""^git version (\d+)\.(\d+)(?:\.(\d+))?""")

        /** Reads `git version 2.45.1`, `git version 2.39.3 (Apple Git-146)`, `git version 2.47.0.windows.1`. */
        public fun parse(output: String): GitVersion? =
            pattern.find(output.trim())?.destructured?.let { (major, minor, patch) ->
                GitVersion(major.toInt(), minor.toInt(), patch.ifEmpty { "0" }.toInt())
            }
    }
}

/** `git --version`. */
public class VersionCommand : GitCommand<GitVersion>() {
    override val function: String = "VERS"
    override val args: List<String> = listOf("--version")

    override fun parse(stdout: ByteArray): GitVersion {
        val text = stdout.toString(Charsets.UTF_8)
        return GitVersion.parse(text) ?: throw GitParseException("unexpected version output \"${text.trim()}\"")
    }
}

/**
 * Checks that git can be started and is at least [GitVersion.MINIMUM]. Failures: `GIT-R-VERS-NF` (git missing),
 * `GIT-R-VERS-DF` (unreadable output), `GIT-R-VERS-VER` (too old).
 */
public suspend fun GitRunner.checkVersion(cwd: Path): Result<GitVersion> {
    val cmd = VersionCommand()
    return when (val result = run(cmd, cwd)) {
        is Result.Success ->
            if (result.data < GitVersion.MINIMUM) {
                Result.Failure(
                    GitVersionException(
                        cmd.function,
                        listOf("git") + cmd.args,
                        result.data.toString(),
                        GitVersion.MINIMUM.toString(),
                    ),
                )
            } else {
                result
            }

        is Result.Failure -> result
    }
}
