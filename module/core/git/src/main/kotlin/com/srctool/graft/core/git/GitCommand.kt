// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.graft.core.exception.GitException
import java.io.InputStream

/** What every git command declares: its arguments, whether it changes the repository, and how it fails. */
public interface GitInvocation {
    /** 2–4 letter verb for error codes: `GIT-R-<function>-<TYPE>`, for example `LOG` or `PUSH`. */
    public val function: String

    /** Arguments after `git` and Graft's global options. Paths come after `--`; nothing goes through a shell. */
    public val args: List<String>

    /** Mutating commands are serialized per repository (ADR-3); reads run in parallel without optional locks. */
    public val mutates: Boolean

    /**
     * The exception for a failure this command recognizes (a rejected push, a conflict), or null to fall back to
     * the runner's general rules.
     */
    public fun failure(argv: List<String>, exitCode: Int, stderr: String): GitException? = null
}

/** A command whose whole output is parsed at once. */
public abstract class GitCommand<out T> : GitInvocation {
    override val mutates: Boolean get() = false

    /** Parses stdout. Pure: no I/O, no git calls. Throws [GitParseException] on output it doesn't understand. */
    public abstract fun parse(stdout: ByteArray): T
}

/** A read-only command whose output is parsed while git is still writing it, such as `git log`. */
public abstract class StreamingGitCommand<out T> : GitInvocation {
    final override val mutates: Boolean get() = false

    /**
     * Parses stdout lazily. The runner consumes the sequence on an I/O thread and closes the stream afterwards.
     * Throws [GitParseException] on output it doesn't understand.
     */
    public abstract fun parse(stdout: InputStream): Sequence<T>
}

/** Thrown by a parser when git's output doesn't match the expected format; the runner reports it as Sinew's `ParseException`. */
public class GitParseException(message: String) : Exception(message)
