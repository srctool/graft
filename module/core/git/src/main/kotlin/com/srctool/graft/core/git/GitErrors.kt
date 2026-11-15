// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

/** Turns a failed git process into the matching [GitException]: the command's own rule first, then the general ones. */
internal fun gitFailure(cmd: GitInvocation, argv: List<String>, exitCode: Int, stderr: String): GitException =
    cmd.failure(argv, exitCode, stderr)
        ?: when (classify(stderr)) {
            Kind.DUBIOUS_OWNERSHIP -> GitUnsafeOwnerException(cmd.function, argv, exitCode, stderr)
            Kind.LOCK -> GitLockedException(cmd.function, argv, exitCode, stderr)
            Kind.NOT_REPOSITORY -> GitNotFoundException(cmd.function, argv, exitCode, stderr)
            Kind.AUTH -> GitAuthException(cmd.function, argv, exitCode, stderr)
            Kind.NETWORK -> GitNetworkException(cmd.function, argv, exitCode, stderr)
            null -> GitExitException(cmd.function, argv, exitCode, stderr)
        }

internal enum class Kind { DUBIOUS_OWNERSHIP, LOCK, NOT_REPOSITORY, AUTH, NETWORK }

// Rules that hold for any command. Order matters: an HTTPS auth failure also says "unable to access".
private val rules: List<Pair<Kind, List<String>>> =
    listOf(
        // Git 2.36+ says "detected dubious ownership"; 2.35.2 said "unsafe repository".
        Kind.DUBIOUS_OWNERSHIP to listOf("detected dubious ownership", "unsafe repository ("),
        Kind.LOCK to listOf(".lock': File exists", "index.lock", "Another git process seems to be running"),
        Kind.NOT_REPOSITORY to listOf("not a git repository"),
        Kind.AUTH to
            listOf(
                "Authentication failed",
                "Permission denied (publickey",
                "could not read Username",
                "could not read Password",
                "terminal prompts disabled",
                "Host key verification failed",
            ),
        Kind.NETWORK to
            listOf(
                "Could not resolve host",
                "Could not resolve hostname",
                "Connection refused",
                "Connection timed out",
                "Network is unreachable",
                "unable to access",
            ),
    )

internal fun classify(stderr: String): Kind? = rules.firstOrNull { (_, needles) -> needles.any { it in stderr } }?.first
