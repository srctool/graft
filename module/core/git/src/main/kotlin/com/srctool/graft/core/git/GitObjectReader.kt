// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.sinew.exception.processCall
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** An object read by a [GitObjectReader]. */
public sealed interface GitObject {
    /** The object's [bytes], exactly as stored (a blob's content, or a commit's raw text). */
    public class Found(public val id: String, public val type: String, public val bytes: ByteArray) : GitObject

    /** No object by that name: an unknown revision, or a path that isn't in it. */
    public data object Missing : GitObject

    /** The object is bigger than the reader was allowed to load; its content wasn't read. */
    public data class TooLarge(val id: String, val type: String, val size: Long) : GitObject
}

/**
 * Reads objects through one long-lived `git cat-file --batch` (Commit Graph: full messages and file contents load on
 * demand), so opening a file's side-by-side view doesn't start a process. One per repository session; reads are taken
 * one at a time. Close it when the session ends.
 */
public interface GitObjectReader : AutoCloseable {
    /**
     * The object [spec] names: an object id, a revision (`HEAD`), or `<revision>:<path>`. Objects over [maxBytes] come
     * back as [GitObject.TooLarge] without being loaded. Fails with `GIT-R-CATF-…` when git can't run there.
     */
    public suspend fun read(spec: String, maxBytes: Long = DEFAULT_MAX_BYTES): Result<GitObject>

    public companion object {
        /** The inline diff size limit's default (Settings Reference): bigger files aren't shown inline. */
        public const val DEFAULT_MAX_BYTES: Long = 2L * 1024 * 1024
    }
}

/** What a `cat-file --batch` process looks like to the error rules. */
internal object CatFileInvocation : GitInvocation {
    override val function = "CATF"
    override val args = listOf("cat-file", "--batch")
    override val mutates = false
}

/**
 * The process is started on the first read and again after it dies, after an object over the limit (its content is
 * skipped by killing git rather than reading it), and after a cancelled read (the answer was half read).
 */
internal class ProcessGitObjectReader(
    private val argv: List<String>,
    private val start: () -> Process,
    private val stderrLimit: Int,
) : GitObjectReader {
    private val mutex = Mutex()

    @Volatile
    internal var currentProcess: Process? = null
        private set

    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var stderr: StderrKeeper? = null

    /** How many times git was started: one per reader unless something forced a restart. */
    @Volatile
    internal var starts: Int = 0
        private set

    @Volatile
    private var closed = false

    override suspend fun read(spec: String, maxBytes: Long): Result<GitObject> =
        processCall("GIT", CatFileInvocation.function, ExceptionLayer.Repository, emptyList()) {
            require(spec.none { it == '\n' || it == '\r' }) { "An object name can't contain a line break" }
            check(!closed) { "This reader was closed" }
            mutex.withLock { readLocked(spec, maxBytes) }
        }

    override fun close() {
        closed = true
        stop()
    }

    private suspend fun readLocked(spec: String, maxBytes: Long): GitObject = coroutineScope {
        if (currentProcess?.isAlive != true) restart()
        val finished = AtomicBoolean(false)
        // Killing git is the only way to unblock a read in progress; a half-read answer can't be resumed anyway.
        val watchdog =
            launch {
                try {
                    awaitCancellation()
                } finally {
                    if (!finished.get()) stop()
                }
            }
        try {
            runInterruptible(Dispatchers.IO) { exchange(spec, maxBytes) }.also { finished.set(true) }
        } finally {
            finished.set(true)
            watchdog.cancel()
        }
    }

    private fun exchange(spec: String, maxBytes: Long): GitObject {
        val out = output ?: throw died()
        val inp = input ?: throw died()
        try {
            out.write("$spec\n".toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (_: IOException) {
            throw died()
        }
        val header = readLine(inp) ?: throw died()
        if (header.endsWith(" missing") || header.endsWith(" ambiguous")) return GitObject.Missing
        val parts = header.split(' ')
        val size = parts.getOrNull(2)?.toLongOrNull()
        if (parts.size != 3 || size == null) throw GitParseException("cat-file header \"$header\"")
        if (size > maxBytes) {
            stop()
            return GitObject.TooLarge(parts[0], parts[1], size)
        }
        val bytes = inp.readNBytes(size.toInt())
        if (bytes.size.toLong() != size || inp.read() != '\n'.code) throw died()
        return GitObject.Found(parts[0], parts[1], bytes)
    }

    private fun restart() {
        stop()
        val process = start()
        starts++
        currentProcess = process
        output = process.outputStream
        input = BufferedInputStream(process.inputStream, 1 shl 16)
        stderr = StderrKeeper(process.errorStream, stderrLimit).also { it.start() }
    }

    /** The process ended: what git said, as the matching exception. The next read starts a new one. */
    private fun died(): GitException {
        val process = currentProcess
        val exit = process?.let { runCatching { it.waitFor() }.getOrDefault(-1) } ?: -1
        val text = stderr?.text().orEmpty()
        stop()
        return gitFailure(CatFileInvocation, argv, if (exit == 0) -1 else exit, text)
    }

    private fun stop() {
        currentProcess?.let { runCatching { it.killTree() } }
        currentProcess = null
        input = null
        output = null
        stderr = null
    }

    private fun readLine(inp: InputStream): String? {
        val line = ByteArrayOutputStream(96)
        while (true) {
            val b = inp.read()
            if (b < 0) return if (line.size() == 0) null else line.toString(Charsets.UTF_8)
            if (b == '\n'.code) return line.toString(Charsets.UTF_8)
            line.write(b)
        }
    }
}

/** Reads a process's stderr on its own thread so git never blocks on it, keeping the first [limit] bytes. */
private class StderrKeeper(private val stream: InputStream, private val limit: Int) : Thread("graft-cat-file-stderr") {
    private val kept = ByteArrayOutputStream()

    init {
        isDaemon = true
    }

    override fun run() {
        try {
            val chunk = ByteArray(4096)
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                synchronized(kept) { if (kept.size() < limit) kept.write(chunk, 0, minOf(n, limit - kept.size())) }
            }
        } catch (_: IOException) {
        }
    }

    fun text(): String {
        join(2_000)
        return synchronized(kept) { kept.toString(Charsets.UTF_8) }
    }
}
