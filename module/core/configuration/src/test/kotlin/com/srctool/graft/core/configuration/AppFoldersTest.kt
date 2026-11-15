// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.configuration

import com.srctool.sinew.models.Result
import com.srctool.sinew.models.errorOrNull
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The OS folders from the Architecture note's "Storage locations", from a fake environment: no real HOME is read. */
class AppFoldersTest {
    // Absolute on every OS ("C:\\" on Windows, "/" elsewhere), so the absolute-path rules behave the same in CI.
    private val root: Path = Paths.get("").toAbsolutePath().root
    private val home = root.resolve("home").resolve("ann")

    private fun abs(vararg parts: String): Path = parts.fold(root) { p, part -> p.resolve(part) }

    private fun absText(vararg parts: String): String = abs(*parts).toString()

    private fun folders(os: OperatingSystem, vararg env: Pair<String, String>) =
        AppFolders.forPlatform(PlatformEnvironment(os, home, mapOf(*env)::get))

    @Test
    fun `macOS uses Application Support, Caches and Logs`() {
        val f = folders(OperatingSystem.MAC)
        assertEquals(home.resolve("Library/Application Support/Graft"), f.data)
        assertEquals(f.data, f.config)
        assertEquals(home.resolve("Library/Caches/Graft"), f.cache)
        assertEquals(home.resolve("Library/Logs/Graft"), f.logs)
    }

    @Test
    fun `Windows uses APPDATA and LOCALAPPDATA`() {
        val f = folders(OperatingSystem.WINDOWS, "APPDATA" to absText("roaming"), "LOCALAPPDATA" to absText("local"))
        assertEquals(abs("roaming", "Graft"), f.data)
        assertEquals(f.data, f.config)
        assertEquals(abs("local", "Graft", "Cache"), f.cache)
        assertEquals(abs("local", "Graft", "Logs"), f.logs)
    }

    @Test
    fun `Windows falls back to the profile folders when the variables are missing`() {
        val f = folders(OperatingSystem.WINDOWS)
        assertEquals(home.resolve("AppData/Roaming/Graft"), f.data)
        assertEquals(home.resolve("AppData/Local/Graft/Cache"), f.cache)
    }

    @Test
    fun `Linux follows the XDG base directories`() {
        val f =
            folders(
                OperatingSystem.LINUX,
                "XDG_CONFIG_HOME" to absText("xdg", "config"),
                "XDG_CACHE_HOME" to absText("xdg", "cache"),
                "XDG_STATE_HOME" to absText("xdg", "state"),
            )
        assertEquals(abs("xdg", "config", "graft"), f.data)
        assertEquals(f.data, f.config)
        assertEquals(abs("xdg", "cache", "graft"), f.cache)
        assertEquals(abs("xdg", "state", "graft", "logs"), f.logs)
    }

    @Test
    fun `Linux ignores empty and relative XDG values, as the spec says`() {
        val f = folders(OperatingSystem.LINUX, "XDG_CONFIG_HOME" to "", "XDG_CACHE_HOME" to "relative/cache")
        assertEquals(home.resolve(".config/graft"), f.data)
        assertEquals(home.resolve(".cache/graft"), f.cache)
        assertEquals(home.resolve(".local/state/graft/logs"), f.logs)
    }

    @Test
    fun `profiles and backups live in the data folder`() {
        val f = folders(OperatingSystem.MAC)
        assertEquals(f.data.resolve("profiles/default"), f.profile("default"))
        assertEquals(f.data.resolve("backups"), f.backups)
    }

    @Test
    fun `a profile id can't leave the profiles folder`() {
        val f = folders(OperatingSystem.MAC)
        listOf("../x", "a/b", "", ".").forEach { id ->
            assertTrue(runCatching { f.profile(id) }.isFailure, "accepted \"$id\"")
        }
    }

    @Test
    fun `the operating system is read from os name`() {
        assertEquals(OperatingSystem.MAC, OperatingSystem.from("Mac OS X"))
        assertEquals(OperatingSystem.WINDOWS, OperatingSystem.from("Windows 11"))
        assertEquals(OperatingSystem.LINUX, OperatingSystem.from("Linux"))
        assertEquals(OperatingSystem.LINUX, OperatingSystem.from("FreeBSD"))
    }

    @Test
    fun `ensure creates a folder and its parents, once`(@TempDir tmp: Path) = runTest {
        val target = tmp.resolve("a b/Grâft/data")
        assertEquals(target, AppFolders.ensure(target).getOrThrow())
        assertTrue(Files.isDirectory(target))
        assertIs<Result.Success<Path>>(AppFolders.ensure(target))
    }

    @Test
    fun `ensure fails with a storage code when a file is in the way`(@TempDir tmp: Path) = runTest {
        val blocker = Files.writeString(tmp.resolve("data"), "not a folder")
        val error = AppFolders.ensure(blocker.resolve("inside")).errorOrNull
        assertEquals("DATA-R-FOLD-SW", error?.code)
    }
}
