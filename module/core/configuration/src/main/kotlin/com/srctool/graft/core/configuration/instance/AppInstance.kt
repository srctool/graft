// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.configuration.instance

import com.srctool.graft.core.configuration.writeHandlers
import com.srctool.sinew.exception.processCall
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val MODULE = "INST"
private const val MAX_LINE_BYTES = 64 * 1024
private const val CONNECTION_SECONDS = 5L
private const val TOKEN_BYTES = 32
private val json = Json { ignoreUnknownKeys = true }

/** One shared timer: a connection that doesn't finish in time is closed, so a silent peer holds nothing. */
private val watchdog: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "graft-instance-watchdog").apply { isDaemon = true } }

/** Reads one `\n`-terminated line of at most [MAX_LINE_BYTES]; null when the peer closes first or sends too much. */
private fun InputStream.readBoundedLine(): String? {
    val line = ByteArrayOutputStream()
    while (true) {
        val b = read()
        if (b < 0) return null
        if (b == '\n'.code) return line.toString(Charsets.UTF_8)
        if (line.size() >= MAX_LINE_BYTES) return null
        line.write(b)
    }
}

private fun SocketChannel.writeLine(text: String) {
    val out = Channels.newOutputStream(this)
    out.write((text + "\n").toByteArray(Charsets.UTF_8))
    out.flush()
}

/**
 * The running app's end of the single-instance socket (Platform Integration 3.5). Whoever holds the folder's lock is
 * the one instance; the lock is the OS's, so a crash releases it and no stale state decides who wins. Each request
 * carries the token from the user-only token file (Threat Model T13).
 */
public class AppInstanceServer private constructor(
    private val folder: InstanceFolder,
    private val server: ServerSocketChannel,
    private val lock: FileLock,
    private val lockChannel: FileChannel,
    private val token: String,
    private val handler: suspend (InstanceRequest) -> InstanceResponse,
) : AutoCloseable {
    @Volatile private var open = true

    private val acceptor =
        thread(name = "graft-instance", isDaemon = true) {
            while (open) {
                val client =
                    try {
                        server.accept()
                    } catch (_: IOException) {
                        return@thread
                    }
                thread(name = "graft-instance-request", isDaemon = true) { serve(client) }
            }
        }

    private fun serve(client: SocketChannel) {
        client.use {
            val timeout = watchdog.schedule({ runCatching { client.close() } }, CONNECTION_SECONDS, TimeUnit.SECONDS)
            try {
                val line = Channels.newInputStream(client).readBoundedLine()
                client.writeLine(json.encodeToString(InstanceResponse.serializer(), answer(line)))
            } catch (_: IOException) {
                // The peer went away or timed out: nothing to answer.
            } finally {
                timeout.cancel(false)
            }
        }
    }

    private fun answer(line: String?): InstanceResponse {
        val request =
            line?.let { runCatching { json.decodeFromString(InstanceRequest.serializer(), it) }.getOrNull() }
                ?: return InstanceResponse(false, "bad request")
        // Compared in constant time; nothing below runs for a wrong or missing token.
        if (!MessageDigest.isEqual(request.token.toByteArray(), token.toByteArray())) {
            return InstanceResponse(false, "not authorized")
        }
        if (request.command == InstanceRequest.PING) return InstanceResponse(true)
        return try {
            kotlinx.coroutines.runBlocking { handler(request) }
        } catch (e: Exception) {
            InstanceResponse(false, e.message ?: "failed")
        }
    }

    /** Stops listening and lets the next launch take over. */
    override fun close() {
        abandon()
        runCatching { Files.deleteIfExists(folder.socket) }
        runCatching { Files.deleteIfExists(folder.token) }
    }

    /** Stops listening and releases the lock but leaves the files, as a crash would. Tests use it. */
    internal fun abandon() {
        open = false
        runCatching { server.close() }
        runCatching { lock.release() }
        runCatching { lockChannel.close() }
    }

    public companion object {
        /**
         * Becomes the one instance, answering [handler] for each valid request; null when another instance already is
         * (the caller then hands its work to it through [AppInstanceClient]).
         */
        public suspend fun bind(
            folder: InstanceFolder,
            handler: suspend (InstanceRequest) -> InstanceResponse,
        ): Result<AppInstanceServer?> =
            processCall(MODULE, "BIND", ExceptionLayer.Repository, writeHandlers(MODULE, "BIND")) {
                folder.ensure()
                val lockPath = folder.dir.resolve("lock")
                val lockChannel =
                    FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                val lock =
                    try {
                        lockChannel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                if (lock == null) {
                    lockChannel.close()
                    return@processCall null
                }
                try {
                    // Holding the lock, anything still here belongs to an instance that is gone.
                    Files.deleteIfExists(folder.socket)
                    val token = newToken()
                    writeToken(folder, token)
                    val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
                    server.bind(UnixDomainSocketAddress.of(folder.socket))
                    if (InstanceFolder.supportsPosix(folder.dir)) {
                        Files.setPosixFilePermissions(folder.socket, InstanceFolder.OWNER_ONLY_FILE)
                    }
                    AppInstanceServer(folder, server, lock, lockChannel, token, handler)
                } catch (e: Throwable) {
                    runCatching { lock.release() }
                    runCatching { lockChannel.close() }
                    throw e
                }
            }

        private fun newToken(): String {
            val bytes = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** Through a temp file and an atomic move, so a reader never sees half a token and the file is never wider than 0600. */
        private fun writeToken(folder: InstanceFolder, token: String) {
            val temp = folder.dir.resolve("token.tmp")
            Files.deleteIfExists(temp)
            Files.createFile(
                temp,
                *if (InstanceFolder.supportsPosix(folder.dir)) {
                    arrayOf(
                        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(InstanceFolder.OWNER_ONLY_FILE),
                    )
                } else {
                    emptyArray()
                },
            )
            Files.writeString(temp, token)
            Files.move(temp, folder.token, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/** A launch's end: hands a request to the running app. */
public class AppInstanceClient(private val folder: InstanceFolder) {
    /**
     * Sends [command] to the running instance and returns its answer, or null when none is running. A launch that
     * just lost the race for the lock can pass [waitMillis] to give the winner time to start listening.
     */
    public suspend fun send(
        command: String,
        paths: List<String> = emptyList(),
        waitMillis: Long = 0,
    ): Result<InstanceResponse?> =
        processCall(MODULE, "SEND", ExceptionLayer.Repository, writeHandlers(MODULE, "SEND")) {
            withContext(Dispatchers.IO) {
                val until = System.nanoTime() + waitMillis * 1_000_000
                while (true) {
                    attempt(command, paths)?.let { return@withContext it }
                    if (System.nanoTime() >= until) return@withContext null
                    Thread.sleep(RETRY_MILLIS)
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
        }

    private fun attempt(command: String, paths: List<String>): InstanceResponse? {
        val token =
            try {
                Files.readString(folder.token).trim()
            } catch (_: IOException) {
                return null
            }
        val channel =
            try {
                SocketChannel.open(StandardProtocolFamily.UNIX).also {
                    it.connect(UnixDomainSocketAddress.of(folder.socket))
                }
            } catch (_: IOException) {
                return null
            }
        return channel.use {
            val timeout = watchdog.schedule({ runCatching { channel.close() } }, CONNECTION_SECONDS, TimeUnit.SECONDS)
            try {
                channel.writeLine(
                    json.encodeToString(InstanceRequest.serializer(), InstanceRequest(token, command, paths)),
                )
                Channels.newInputStream(channel).readBoundedLine()
                    ?.let { runCatching { json.decodeFromString(InstanceResponse.serializer(), it) }.getOrNull() }
            } catch (_: ClosedChannelException) {
                null
            } catch (_: IOException) {
                null
            } finally {
                timeout.cancel(false)
            }
        }
    }

    private companion object {
        const val RETRY_MILLIS = 100L
    }
}
