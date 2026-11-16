// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.graft.core.exception.CommandOutput
import com.srctool.graft.core.exception.GraftCodes
import com.srctool.graft.core.exception.GraftCodes.Types
import com.srctool.graft.core.exception.Redaction
import com.srctool.sinew.models.ErrorMessage
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.SinewException

/**
 * A git process failed, or couldn't run. Every git failure is one of these subclasses, with a code like
 * `GIT-R-PUSH-REJ`: the git engine is a data source, so the layer is `R` (ADR-23).
 *
 * Git's own output is kept for "Show output": [argv] without the environment, [exitCode] (null when git couldn't be
 * started), and [stderr]. All three are redacted when the exception is created, so it can be logged or shown as is.
 */
public sealed class GitException(
    function: String,
    argv: List<String>,
    override val exitCode: Int?,
    stderr: String,
    cause: Throwable? = null,
) : SinewException("GIT", ExceptionLayer.Repository, GraftCodes.requireFunction(function), cause),
    CommandOutput {
    override val argv: List<String> = argv.map(Redaction::redact)
    override val stderr: String = Redaction.redact(stderr)

    /** Git's first `fatal:` or `error:` line, or an empty string. Technical detail for "Show output", not the message. */
    public val summary: String =
        this.stderr
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("fatal:") || it.startsWith("error:") }
            .orEmpty()

    protected fun local(key: String, fallback: String): ErrorMessage =
        ErrorMessage.Local(key = "git.error.$key", fallback = fallback)
}

/** The remote refused the update (non-fast-forward, protected branch, hook). */
public class GitRejectedException(function: String, argv: List<String>, exitCode: Int?, stderr: String) :
    GitException(function, argv, exitCode, stderr) {
    override val typeCode: String = Types.REJECTED
    override val errorMessage: ErrorMessage = local("rejected", "The remote rejected the change.")
}

/** Authentication failed or needs a prompt git isn't allowed to show. */
public class GitAuthException(function: String, argv: List<String>, exitCode: Int?, stderr: String) :
    GitException(function, argv, exitCode, stderr) {
    override val typeCode: String = Types.AUTH
    override val errorMessage: ErrorMessage = local("auth", "Git couldn't sign in to the remote.")
}

/** The operation stopped on conflicts. */
public class GitConflictException(function: String, argv: List<String>, exitCode: Int?, stderr: String) :
    GitException(function, argv, exitCode, stderr) {
    override val typeCode: String = Types.CONFLICT
    override val errorMessage: ErrorMessage = local("conflict", "The operation stopped on conflicts.")
}

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

/** Another git process holds a lock (`index.lock`, a ref lock). */
public class GitLockedException(function: String, argv: List<String>, exitCode: Int?, stderr: String) :
    GitException(function, argv, exitCode, stderr) {
    override val typeCode: String = Types.LOCKED
    override val retryable: Boolean get() = true
    override val errorMessage: ErrorMessage = local("locked", "Another Git process is running in this repository.")
}

/** The folder isn't a repository, or the `git` executable isn't there ([exitCode] is null then). */
public class GitNotFoundException(
    function: String,
    argv: List<String>,
    exitCode: Int?,
    stderr: String,
    cause: Throwable? = null,
) : GitException(function, argv, exitCode, stderr, cause) {
    override val typeCode: String = Types.NOT_FOUND
    override val errorMessage: ErrorMessage =
        if (exitCode ==
            null
        ) {
            local("missing", "Git isn't installed, or Graft can't start it.")
        } else {
            local("notRepository", "This folder isn't a Git repository.")
        }
}

/** The remote couldn't be reached. */
public class GitNetworkException(function: String, argv: List<String>, exitCode: Int?, stderr: String) :
    GitException(function, argv, exitCode, stderr) {
    override val typeCode: String = Types.NETWORK
    override val retryable: Boolean get() = true
    override val errorMessage: ErrorMessage = local("network", "Git couldn't reach the remote.")
}

/** The operation took longer than allowed. */
public class GitTimeoutException(function: String, argv: List<String>, exitCode: Int?, stderr: String) :
    GitException(function, argv, exitCode, stderr) {
    override val typeCode: String = Types.TIMEOUT
    override val retryable: Boolean get() = true
    override val errorMessage: ErrorMessage = local("timeout", "Git took too long to answer.")
}

/** The installed Git is older than Graft supports. */
public class GitVersionException(
    function: String,
    argv: List<String>,
    public val found: String,
    public val required: String,
) : GitException(function, argv, exitCode = 0, stderr = "") {
    override val typeCode: String = Types.VERSION
    override val errorMessage: ErrorMessage =
        ErrorMessage.Local(
            key = "git.error.version",
            args = mapOf("found" to found, "required" to required),
            fallback = "Git {found} is too old; Graft needs Git {required} or newer.",
        )
}

/** Git exited non-zero for a reason no rule recognized; [summary] and [stderr] say what git said. */
public class GitExitException(function: String, argv: List<String>, exitCode: Int?, stderr: String) :
    GitException(function, argv, exitCode, stderr) {
    override val typeCode: String = Types.EXIT
    override val errorMessage: ErrorMessage =
        ErrorMessage.Local(
            key = "git.error.exit",
            args = mapOf("exitCode" to (exitCode ?: -1)),
            fallback = "Git stopped with exit code {exitCode}.",
        )
}
