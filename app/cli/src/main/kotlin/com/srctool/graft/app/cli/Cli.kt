// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.app.cli

import com.srctool.graft.core.configuration.AppFolders
import com.srctool.graft.core.configuration.instance.AppInstanceClient
import com.srctool.graft.core.configuration.instance.InstanceFolder
import com.srctool.graft.core.configuration.instance.InstanceRequest
import com.srctool.sinew.models.Result
import kotlinx.coroutines.runBlocking
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/** Exit codes from the CLI note: 0 ok, 1 error, 2 usage error. */
internal object Exit {
    const val OK = 0
    const val ERROR = 1
    const val USAGE = 2
}

/**
 * `graft` and `graft open [path]` (D6, a JVM launcher): asks the running app to open a tab, or starts the app with
 * the path when none runs. Everything the machine decides is passed in, so tests run it against a temp folder.
 */
internal class Cli(
    private val folder: InstanceFolder,
    private val cwd: Path,
    private val appCommand: () -> List<String>?,
    private val start: (List<String>) -> Unit,
    private val out: PrintStream,
    private val err: PrintStream,
) {
    fun run(args: List<String>): Int = when (args.firstOrNull()) {
        null -> open(cwd)
        "open" -> if (args.size <= 2) open(args.getOrNull(1)?.let(cwd::resolve) ?: cwd) else usage()
        else -> usage()
    }

    private fun usage(): Int {
        err.println("Usage: graft [open [path]]")
        return Exit.USAGE
    }

    private fun open(path: Path): Int {
        val target = path.toAbsolutePath().normalize()
        return when (
            val sent = runBlocking {
                AppInstanceClient(folder).send(InstanceRequest.OPEN, listOf(target.toString()))
            }
        ) {
            is Result.Failure -> {
                err.println("Couldn't reach Graft: ${sent.error.code}")
                Exit.ERROR
            }

            is Result.Success ->
                when (val answer = sent.data) {
                    null -> launch(target)

                    else ->
                        if (answer.ok) {
                            Exit.OK
                        } else {
                            err.println("Graft refused: ${answer.error}")
                            Exit.ERROR
                        }
                }
        }
    }

    /** No app is running: the new one is the first instance, so it opens the path itself. */
    private fun launch(target: Path): Int {
        val command = appCommand()
        if (command == null) {
            err.println("Graft isn't installed next to this command. Set GRAFT_APP to the app's launcher.")
            return Exit.ERROR
        }
        return try {
            start(command + target.toString())
            Exit.OK
        } catch (e: java.io.IOException) {
            err.println("Couldn't start Graft: ${e.message}")
            Exit.ERROR
        }
    }
}

/** `GRAFT_APP`, else `graft-desktop` beside this command's own jar. */
internal fun defaultAppCommand(env: (String) -> String? = System::getenv): List<String>? {
    env("GRAFT_APP")?.takeIf { it.isNotBlank() }?.let { return listOf(it) }
    val here = runCatching {
        Paths.get(Cli::class.java.protectionDomain.codeSource.location.toURI()).parent
    }.getOrNull()
    val sibling = here?.resolve(
        if (System.getProperty("os.name").startsWith("Windows")) "graft-desktop.bat" else "graft-desktop",
    )
    return sibling?.takeIf(Files::isExecutable)?.let { listOf(it.toString()) }
}

/** Starts [command] detached from this process, with its output discarded. */
internal fun startDetached(command: List<String>) {
    ProcessBuilder(command)
        .redirectInput(
            ProcessBuilder.Redirect.from(
                java.io.File(if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null"),
            ),
        )
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
}

fun main(args: Array<String>) {
    val folder = InstanceFolder.of(
        AppFolders.current(),
        Paths.get(System.getProperty("java.io.tmpdir")),
        System.getProperty("user.name").orEmpty(),
    )
    val cli = Cli(folder, Paths.get("").toAbsolutePath(), ::defaultAppCommand, ::startDetached, System.out, System.err)
    exitProcess(cli.run(args.toList()))
}
