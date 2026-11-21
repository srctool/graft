// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.app.cli

import com.srctool.graft.core.configuration.instance.AppInstanceServer
import com.srctool.graft.core.configuration.instance.InstanceFolder
import com.srctool.graft.core.configuration.instance.InstanceResponse
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `graft` and `graft open`, against a real instance server in a temp folder: nothing here touches the user's cache. */
class CliTest {
    @TempDir
    lateinit var temp: Path

    private val errors = ByteArrayOutputStream()
    private val opened = CopyOnWriteArrayList<List<String>>()
    private val started = CopyOnWriteArrayList<List<String>>()
    private var server: AppInstanceServer? = null

    @AfterTest
    fun stop() {
        server?.close()
    }

    private fun folder() = InstanceFolder(temp.resolve("run"))

    private fun cli(appCommand: List<String>? = listOf("graft-desktop")) = Cli(folder(), temp.resolve("work"), {
        appCommand
    }, { started += it }, PrintStream(ByteArrayOutputStream()), PrintStream(errors))

    private fun listen(answer: InstanceResponse = InstanceResponse(ok = true)) {
        server =
            runBlocking {
                AppInstanceServer.bind(folder()) { request ->
                    opened += request.paths
                    answer
                }.getOrThrow()
            }
    }

    @Test
    fun `open opens a tab in the running app, with the path made absolute`() {
        listen()
        assertEquals(Exit.OK, cli().run(listOf("open", "app")))
        assertEquals(listOf(listOf(temp.resolve("work").resolve("app").toString())), opened)
        assertEquals(emptyList(), started)
    }

    @Test
    fun `no arguments opens the current folder`() {
        listen()
        assertEquals(Exit.OK, cli().run(emptyList()))
        assertEquals(listOf(listOf(temp.resolve("work").toString())), opened)
    }

    @Test
    fun `with no app running, the app is started with the path`() {
        assertEquals(Exit.OK, cli().run(listOf("open", "app")))
        assertEquals(listOf(listOf("graft-desktop", temp.resolve("work").resolve("app").toString())), started)
    }

    @Test
    fun `with no app running and none to start, it says so and fails`() {
        assertEquals(Exit.ERROR, cli(appCommand = null).run(listOf("open")))
        assertTrue(errors.toString().contains("GRAFT_APP"))
    }

    @Test
    fun `an app that refuses the request is an error`() {
        listen(InstanceResponse(ok = false, error = "no way"))
        assertEquals(Exit.ERROR, cli().run(listOf("open", "app")))
        assertTrue(errors.toString().contains("no way"))
    }

    @Test
    fun `unknown commands and extra arguments are usage errors`() {
        assertEquals(Exit.USAGE, cli().run(listOf("frobnicate")))
        assertEquals(Exit.USAGE, cli().run(listOf("open", "a", "b")))
        assertTrue(errors.toString().contains("Usage: graft"))
    }

    @Test
    fun `the real launcher starts a program and hands it the path`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "a shell script")
        val log = temp.resolve("args.txt")
        val script = temp.resolve("fake-app")
        Files.writeString(script, "#!/bin/sh\nprintf '%s\\n' \"\$@\" > '$log'\n")
        script.toFile().setExecutable(true)
        val cli =
            Cli(folder(), temp.resolve("work"), {
                listOf(script.toString())
            }, ::startDetached, PrintStream(ByteArrayOutputStream()), PrintStream(errors))
        assertEquals(Exit.OK, cli.run(listOf("open", "app")))
        val deadline = System.nanoTime() + 10_000_000_000
        while (!Files.exists(log) && System.nanoTime() < deadline) Thread.sleep(50)
        assertEquals(temp.resolve("work").resolve("app").toString(), Files.readString(log).trim())
    }

    @Test
    fun `GRAFT_APP names the app to start`() {
        assertEquals(
            listOf("/opt/graft/launch"),
            defaultAppCommand {
                if (it ==
                    "GRAFT_APP"
                ) {
                    "/opt/graft/launch"
                } else {
                    null
                }
            },
        )
    }
}
