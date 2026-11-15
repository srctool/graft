// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** How [ProcessGitRunner] starts git. The defaults are what the app uses. */
public data class GitRunnerConfig(
    /** The git executable: a name looked up on `PATH`, or a path. */
    val executable: String = "git",
    /**
     * Applied on top of the inherited environment, before Graft's own settings (which always win); a null value
     * removes the variable. Tests use this for the sandbox.
     */
    val environment: Map<String, String?> = emptyMap(),
    val helpers: GitHelpers = GitHelpers.Refusing,
    val launcher: ProcessLauncher = ProcessLauncher.Default,
    /** At most this much of stderr is kept for errors and "Show output". */
    val stderrLimit: Int = 1 shl 20,
)

/**
 * The programs git and ssh call back into: the askpass helper for credential, passphrase and host-key prompts, and
 * the editor for messages and rebase todo lists.
 *
 * Graft's own helpers arrive in G3 (`graft askpass`); until then [Refusing] makes every prompt fail at once instead
 * of hanging on a terminal.
 */
public data class GitHelpers(
    /** Set as `GIT_ASKPASS` and `SSH_ASKPASS` (with `SSH_ASKPASS_REQUIRE=force`); null leaves them alone. */
    val askpass: String?,
    /** Set as `GIT_EDITOR`. Git runs it itself; Graft never does. */
    val editor: String,
) {
    public companion object {
        /** Answers no prompt: askpass and editor both exit non-zero. */
        public val Refusing: GitHelpers = GitHelpers(askpass = posixFalse(), editor = "false")

        // Windows has no fixed path for a "false" program; there GIT_TERMINAL_PROMPT=0 has to do until G3.
        private fun posixFalse(): String? =
            listOf("/usr/bin/false", "/bin/false").firstOrNull { Files.isExecutable(Paths.get(it)) }
    }
}

/** Starts processes. Tests replace it to keep every process inside the sandbox. */
public fun interface ProcessLauncher {
    public fun start(argv: List<String>, cwd: Path, environment: Map<String, String>): Process

    public companion object {
        public val Default: ProcessLauncher =
            ProcessLauncher { argv, cwd, environment ->
                ProcessBuilder(argv)
                    .directory(cwd.toFile())
                    .apply {
                        environment().clear()
                        environment().putAll(environment)
                    }.start()
            }
    }
}
