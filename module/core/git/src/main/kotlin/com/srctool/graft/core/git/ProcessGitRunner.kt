// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.graft.core.exception.GitException
import com.srctool.graft.core.exception.GitNotFoundException
import com.srctool.sinew.exception.CrashReporter
import com.srctool.sinew.exception.ExceptionHandler
import com.srctool.sinew.exception.ParseException
import com.srctool.sinew.exception.processCall
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** The [GitRunner] that starts real git processes. */
public class ProcessGitRunner(
    private val config: GitRunnerConfig = GitRunnerConfig(),
    private val writeQueue: WriteQueue = WriteQueue(),
) : GitRunner {
    private val repositoryKeys = ConcurrentHashMap<Path, String>()

    // Sinew's guard: a GitException passes through, cancellation propagates, a parse failure becomes ParseException
    // and anything unexpected becomes UnexpectedException; both of those are reported to the CrashReporter.
    override suspend fun <T> run(cmd: GitCommand<T>, cwd: Path, input: ByteArray?): Result<T> =
        processCall(MODULE, cmd.function, ExceptionLayer.Repository, parseHandler(cmd)) {
            if (cmd.mutates) {
                writeQueue.withWrite(repositoryKey(cwd)) { exec(cmd, cwd, input) }
            } else {
                exec(cmd, cwd, input)
            }
        }

    override fun <T> stream(cmd: StreamingGitCommand<T>, cwd: Path): Flow<T> = flow {
        val argv = argv(cmd)
        val process = start(cmd, argv, cwd)
        coroutineScope {
            val killer = killOnCancel(process)
            val stderr = async { process.errorStream.readCapped(config.stderrLimit) }
            process.outputStream.close()
            try {
                process.inputStream.use { stdout -> cmd.parse(stdout).forEach { emit(it) } }
            } catch (e: GitParseException) {
                throw ParseException(MODULE, ExceptionLayer.Repository, cmd.function, e).also(::report)
            } catch (e: IOException) {
                ensureActive()
                throw e
            }
            val exitCode = process.onExit().await().exitValue()
            val errText = stderr.await()
            killer.cancel()
            if (exitCode != 0) throw gitFailure(cmd, argv, exitCode, errText)
        }
    }.flowOn(Dispatchers.IO)

    /** Runs [cmd] and returns its parsed output; throws [GitException] when git fails. */
    private suspend fun <T> exec(cmd: GitCommand<T>, cwd: Path, input: ByteArray?): T = withContext(Dispatchers.IO) {
        val argv = argv(cmd)
        val process = start(cmd, argv, cwd)
        coroutineScope {
            val killer = killOnCancel(process)
            val stdout = async { process.inputStream.use(InputStream::readBytes) }
            val stderr = async { process.errorStream.readCapped(config.stderrLimit) }
            launch {
                // A process may exit without reading its input; that's its answer, not a runner failure.
                try {
                    process.outputStream.use { stdin -> input?.let(stdin::write) }
                } catch (_: IOException) {
                }
            }
            val exitCode = process.onExit().await().exitValue()
            val out = stdout.await()
            val err = stderr.await()
            killer.cancel()
            if (exitCode != 0) throw gitFailure(cmd, argv, exitCode, err)
            cmd.parse(out)
        }
    }

    private fun start(cmd: GitInvocation, argv: List<String>, cwd: Path): Process = try {
        config.launcher.start(argv, cwd, environment(cmd.mutates))
    } catch (e: IOException) {
        throw GitNotFoundException(cmd.function, argv, exitCode = null, stderr = e.message.orEmpty(), cause = e)
    }

    internal fun argv(cmd: GitInvocation): List<String> = listOf(config.executable) + GLOBAL_OPTIONS + cmd.args

    // Inherited variables, then the caller's overrides, then Graft's own settings last: an override (or the user's
    // shell) must never undo LC_ALL, the prompt rules or the helpers.
    internal fun environment(mutates: Boolean): Map<String, String> {
        val env = HashMap(System.getenv())
        config.environment.forEach { (key, value) -> if (value == null) env.remove(key) else env[key] = value }
        env["LC_ALL"] = "C"
        env["GIT_TERMINAL_PROMPT"] = "0"
        if (mutates) env.remove("GIT_OPTIONAL_LOCKS") else env["GIT_OPTIONAL_LOCKS"] = "0"
        env["GIT_EDITOR"] = config.helpers.editor
        config.helpers.askpass?.let {
            env["GIT_ASKPASS"] = it
            env["SSH_ASKPASS"] = it
            env["SSH_ASKPASS_REQUIRE"] = "force"
        }
        return env
    }

    /**
     * Writes are queued per repository, and worktrees of one repository share refs, so the key is the common git
     * dir. Folders that aren't repositories yet (clone, init) are keyed by their own path.
     */
    private suspend fun repositoryKey(cwd: Path): String {
        val dir = cwd.toAbsolutePath().normalize()
        repositoryKeys[dir]?.let { return it }
        val key =
            try {
                exec(CommonDirCommand, dir, null)
            } catch (_: GitException) {
                dir.toString()
            }
        repositoryKeys[dir] = key
        return key
    }

    private object CommonDirCommand : GitCommand<String>() {
        override val function = "CDIR"
        override val args = listOf("rev-parse", "--path-format=absolute", "--git-common-dir")

        override fun parse(stdout: ByteArray): String = stdout.toString(Charsets.UTF_8).trim()
    }

    private companion object {
        const val MODULE = "GIT"

        // Stable, unescaped, colorless output, and never a pager.
        val GLOBAL_OPTIONS = listOf("--no-pager", "-c", "core.quotepath=false", "-c", "color.ui=never")

        fun parseHandler(cmd: GitInvocation): List<ExceptionHandler> = listOf(
            ExceptionHandler({
                it is GitParseException
            }) { ParseException(MODULE, ExceptionLayer.Repository, cmd.function, it) },
        )

        // The stream can't go through processCall (it throws to its collector), so it reports parse failures itself.
        fun report(e: ParseException) {
            try {
                CrashReporter.current.recordNonFatal(e)
            } catch (_: Throwable) {
            }
        }
    }
}

/** Kills [process] and everything it started when the calling scope is cancelled. Cancel the job once it's done. */
private fun CoroutineScope.killOnCancel(process: Process): Job = launch {
    try {
        awaitCancellation()
    } finally {
        process.killTree()
    }
}

/** Children first: once the parent dies they'd be reparented and lost. */
internal fun Process.killTree() {
    val children = descendants().toList()
    children.forEach { it.destroyForcibly() }
    destroyForcibly()
}

private fun InputStream.readCapped(limit: Int): String = use { stream ->
    val buffer = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    while (true) {
        val n = stream.read(chunk)
        if (n < 0) break
        // Keep reading past the limit so git never blocks on a full pipe; just stop keeping it.
        val room = limit - buffer.size()
        if (room > 0) buffer.write(chunk, 0, minOf(n, room))
    }
    buffer.toString(Charsets.UTF_8)
}
