// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.configuration.instance

import com.srctool.graft.core.configuration.AppFolders
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The single-instance socket, over real Unix domain sockets in a temp folder (never the user's cache). */
class AppInstanceTest {
    @TempDir
    lateinit var temp: Path

    private val servers = mutableListOf<AppInstanceServer>()
    private val received = CopyOnWriteArrayList<InstanceRequest>()

    @AfterTest
    fun stop() = servers.forEach { it.close() }

    private fun folder() = InstanceFolder(temp.resolve("run"))

    private fun serve(folder: InstanceFolder): AppInstanceServer? {
        val server =
            runBlocking {
                AppInstanceServer.bind(folder) { request ->
                    received += request
                    InstanceResponse(ok = true)
                }.getOrThrow()
            }
        server?.let(servers::add)
        return server
    }

    private fun posix() = assumeTrue(InstanceFolder.supportsPosix(temp), "POSIX permissions")

    @Test
    fun `the folder is for the user alone, and so are the socket and the token`() {
        posix()
        val folder = folder()
        serve(folder)
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
            Files.getPosixFilePermissions(folder.dir),
        )
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(folder.token),
        )
        assertTrue(
            Files.getPosixFilePermissions(folder.socket).none {
                it.name.startsWith("GROUP") ||
                    it.name.startsWith("OTHERS")
            },
        )
    }

    @Test
    fun `a request with the token is handled and answered`() {
        val folder = folder()
        serve(folder)
        val answer = runBlocking {
            AppInstanceClient(folder).send(InstanceRequest.OPEN, listOf("/work/app"))
        }.getOrThrow()
        assertEquals(InstanceResponse(ok = true), answer)
        assertEquals(listOf("/work/app"), received.single().paths)
    }

    @Test
    fun `a request without the token, or with a wrong one, is refused and not handled`() {
        val folder = folder()
        serve(folder)
        listOf("", "0".repeat(64)).forEach { wrong ->
            val answer = raw(folder, """{"token":"$wrong","command":"open","paths":["/x"]}""")
            assertTrue(answer.contains(""""ok":false"""), answer)
        }
        assertEquals(emptyList(), received)
    }

    @Test
    fun `a malformed or oversize line is refused`() {
        val folder = folder()
        serve(folder)
        assertTrue(raw(folder, "not json").contains(""""ok":false"""))
        // Too long to be a request: refused, or the connection is dropped while it is still being sent.
        val answer = runCatching { raw(folder, "x".repeat(200_000)) }.getOrNull()
        assertTrue(answer == null || answer.contains(""""ok":false"""), answer)
        assertEquals(emptyList(), received)
    }

    @Test
    fun `a second launch hands off to the first, which is still the only server`() {
        val folder = folder()
        assertNotNull(serve(folder))
        assertNull(serve(folder), "a live instance answers, so the second bind yields to it")
        val answer = runBlocking {
            AppInstanceClient(folder).send(InstanceRequest.OPEN, listOf("/a", "/b"))
        }.getOrThrow()
        assertEquals(true, answer?.ok)
        assertEquals(listOf("/a", "/b"), received.single().paths)
    }

    @Test
    fun `with no instance there is nothing to hand off to`() {
        assertNull(runBlocking { AppInstanceClient(folder()).send(InstanceRequest.OPEN, emptyList()) }.getOrThrow())
    }

    @Test
    fun `a stale socket file from a crashed app is replaced`() {
        val folder = folder()
        val first = serve(folder)!!
        // A crash leaves the socket file behind without anyone listening.
        first.abandon()
        assertTrue(Files.exists(folder.socket))
        assertNotNull(serve(folder))
        assertEquals(true, runBlocking { AppInstanceClient(folder).send(InstanceRequest.PING) }.getOrThrow()?.ok)
    }

    @Test
    fun `each start writes a new token, so an old one stops working`() {
        val folder = folder()
        val first = serve(folder)!!
        val old = Files.readString(folder.token)
        first.close()
        serve(folder)
        assertFalse(old == Files.readString(folder.token))
        val answer = raw(folder, """{"token":"$old","command":"open"}""")
        assertTrue(answer.contains(""""ok":false"""))
    }

    @Test
    fun `a long cache path moves the socket to a short folder in the temp directory`() {
        val long = temp.resolve("x".repeat(120))
        val folders = AppFolders(long, long, long.resolve("cache"), long.resolve("logs"))
        val chosen = InstanceFolder.of(folders, temp.resolve("tmp"), "ann doe")
        assertEquals(temp.resolve("tmp").resolve("graft-anndoe"), chosen.dir)
        assertTrue(
            InstanceFolder.of(
                AppFolders(temp, temp, temp.resolve("c"), temp.resolve("l")),
                temp,
                "ann",
            ).dir.endsWith("run"),
        )
    }

    private fun raw(folder: InstanceFolder, line: String): String =
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(folder.socket))
            val out = Channels.newOutputStream(channel)
            out.write((line + "\n").toByteArray())
            out.flush()
            Channels.newInputStream(channel).bufferedReader().readLine().orEmpty()
        }
}
