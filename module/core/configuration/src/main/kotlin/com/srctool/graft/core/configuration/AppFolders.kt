// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.configuration

import com.srctool.sinew.exception.ExceptionHandler
import com.srctool.sinew.exception.StorageFullException
import com.srctool.sinew.exception.StorageWriteException
import com.srctool.sinew.exception.processCall
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** The operating systems Graft builds for. Anything else that isn't macOS or Windows is treated as Linux. */
public enum class OperatingSystem {
    MAC,
    WINDOWS,
    LINUX,
    ;

    public companion object {
        /** From the JVM's `os.name`. */
        public fun from(osName: String): OperatingSystem = when {
            osName.startsWith("Mac", ignoreCase = true) || osName.startsWith("Darwin", ignoreCase = true) -> MAC
            osName.startsWith("Windows", ignoreCase = true) -> WINDOWS
            else -> LINUX
        }

        /** The OS this JVM runs on. */
        public val current: OperatingSystem by lazy { from(System.getProperty("os.name").orEmpty()) }
    }
}

/**
 * What [AppFolders] reads from the machine: the OS, the home folder, and environment variables. Tests pass their own,
 * so no test ever reads the developer's real home.
 */
public class PlatformEnvironment(
    public val os: OperatingSystem,
    public val home: Path,
    public val variable: (String) -> String?,
) {
    public companion object {
        /** This machine. */
        public fun current(): PlatformEnvironment =
            PlatformEnvironment(OperatingSystem.current, Paths.get(System.getProperty("user.home")), System::getenv)
    }
}

/**
 * Graft's folders on this OS (Architecture, "Storage locations"). Settings, window state, recent repositories and
 * profiles go in [data]; [cache] is safe to delete; [logs] holds the rotated log files. Folders are created on first
 * use with [ensure], never by reading these paths.
 */
public class AppFolders(
    /** App settings, app state and `profiles/`. */
    public val data: Path,
    /** Configuration Graft writes for other tools to read; the same folder as [data] on every OS today. */
    public val config: Path,
    /** Caches (SQLite, avatars): safe to delete. */
    public val cache: Path,
    public val logs: Path,
) {
    /** Where profile [id]'s files live (`profiles/<id>/`). Rejects ids that would leave the folder. */
    public fun profile(id: String): Path {
        require(id.isNotEmpty() && id != "." && id != ".." && id.none { it == '/' || it == '\\' }) {
            "Not a profile id: \"$id\""
        }
        return data.resolve(PROFILES).resolve(id)
    }

    /** Copies taken before a migration (Storage Formats 3.1, rule 5). */
    public val backups: Path get() = data.resolve(BACKUPS)

    public companion object {
        private const val MODULE = "DATA"
        private const val PROFILES = "profiles"
        private const val BACKUPS = "backups"

        /** This machine's folders. */
        public fun current(): AppFolders = forPlatform(PlatformEnvironment.current())

        /** The folders for [env]. */
        public fun forPlatform(env: PlatformEnvironment): AppFolders {
            val home = env.home
            return when (env.os) {
                OperatingSystem.MAC -> {
                    val data = home.resolve("Library/Application Support/Graft")
                    AppFolders(data, data, home.resolve("Library/Caches/Graft"), home.resolve("Library/Logs/Graft"))
                }

                OperatingSystem.WINDOWS -> {
                    val roaming = env.absolute("APPDATA") ?: home.resolve("AppData/Roaming")
                    val local = env.absolute("LOCALAPPDATA") ?: home.resolve("AppData/Local")
                    val data = roaming.resolve("Graft")
                    AppFolders(data, data, local.resolve("Graft/Cache"), local.resolve("Graft/Logs"))
                }

                // XDG base directories: a variable that is empty or relative is ignored.
                OperatingSystem.LINUX -> {
                    val data = (env.absolute("XDG_CONFIG_HOME") ?: home.resolve(".config")).resolve("graft")
                    val cache = (env.absolute("XDG_CACHE_HOME") ?: home.resolve(".cache")).resolve("graft")
                    val state = (env.absolute("XDG_STATE_HOME") ?: home.resolve(".local/state")).resolve("graft")
                    AppFolders(data, data, cache, state.resolve("logs"))
                }
            }
        }

        /**
         * Creates [folder] and its parents if they're missing, and returns it. Fails with `DATA-R-FOLD-SW`, or
         * `…-SF` when the disk is full.
         */
        public suspend fun ensure(folder: Path): Result<Path> =
            processCall(MODULE, "FOLD", ExceptionLayer.Repository, writeHandlers(MODULE, "FOLD")) {
                withContext(Dispatchers.IO) { Files.createDirectories(folder) }
            }

        private fun PlatformEnvironment.absolute(name: String): Path? =
            variable(name)?.takeIf { it.isNotBlank() }?.let(Paths::get)?.takeIf(Path::isAbsolute)
    }
}

/** I/O failures while writing: a full disk is its own type, anything else is a write failure. */
internal fun writeHandlers(module: String, function: String): List<ExceptionHandler> = listOf(
    ExceptionHandler({ it is IOException && it.isDiskFull() }) {
        StorageFullException(module, ExceptionLayer.Repository, function, it)
    },
    ExceptionHandler({ it is IOException }) { StorageWriteException(module, ExceptionLayer.Repository, function, it) },
)

private fun IOException.isDiskFull(): Boolean = generateSequence<Throwable>(this) { it.cause }.any { e ->
    val text = e.message.orEmpty()
    text.contains("No space left", ignoreCase = true) || text.contains("not enough space", ignoreCase = true)
}
