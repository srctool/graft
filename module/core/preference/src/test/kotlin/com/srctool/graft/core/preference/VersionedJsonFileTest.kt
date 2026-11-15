// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.preference

import com.srctool.sinew.models.errorOrNull
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Storage Formats rules (3.1 and the edge cases) on a real file in a temp folder. */
class VersionedJsonFileTest {
    @TempDir
    lateinit var dir: Path

    @Serializable
    data class Prefs(val theme: String = "system", val rows: Int = 24)

    /** A payload that would clash with the envelope's own `version`. */
    @Serializable
    data class Bad(val version: Int = 9)

    private val path get() = dir.resolve("settings.json")

    private fun TestScope.file(version: Int = 1) = VersionedJsonFile(
        path = path,
        version = version,
        serializer = Prefs.serializer(),
        defaults = { Prefs() },
        dispatcher = StandardTestDispatcher(testScheduler),
        clock = { Instant.parse("2026-11-15T10:20:30Z") },
    )

    private fun bytes() = Files.readAllBytes(path)

    @Test
    fun `a missing file reads as defaults`() = runTest {
        val stored = file().read().getOrThrow()
        assertEquals(Prefs(), stored.value)
        assertEquals(StoredOrigin.Missing, stored.origin)
        assertTrue(!Files.exists(path), "reading never creates the file")
    }

    @Test
    fun `written values read back, with the version first`() = runTest {
        file().write(Prefs("dark", 28)).getOrThrow()
        val json = Json.parseToJsonElement(Files.readString(path)).jsonObject
        assertEquals("version", json.keys.first())
        assertEquals(1, json.getValue("version").jsonPrimitive.int)
        val stored = file().read().getOrThrow()
        assertEquals(Prefs("dark", 28), stored.value)
        assertEquals(StoredOrigin.Current, stored.origin)
    }

    @Test
    fun `unknown fields are ignored, and missing ones take their defaults`() = runTest {
        Files.writeString(path, """{"version":1,"theme":"light","addedLater":{"x":1}}""")
        assertEquals(Prefs("light", 24), file().read().getOrThrow().value)
    }

    @Test
    fun `a newer version is read but never rewritten`() = runTest {
        Files.writeString(path, """{"version":3,"theme":"dark","rows":28,"newThing":true}""")
        val before = bytes()
        val stored = file().read().getOrThrow()
        assertEquals(Prefs("dark", 28), stored.value)
        assertEquals(StoredOrigin.Newer(3), stored.origin)
        assertTrue(stored.readOnly)

        val error = assertIs<StoredFormatTooNewException>(file().write(Prefs()).errorOrNull)
        assertEquals("DATA-R-WRIT-VER", error.code)
        assertEquals(3, error.foundVersion)
        assertEquals(1, error.supportedVersion)
        assertContentEquals(before, bytes())
    }

    @Test
    fun `a newer version this Graft can't decode still isn't touched`() = runTest {
        Files.writeString(path, """{"version":2,"theme":{"mode":"dark"}}""")
        val before = bytes()
        val stored = file().read().getOrThrow()
        assertEquals(Prefs(), stored.value)
        assertEquals(StoredOrigin.Newer(2), stored.origin)
        assertContentEquals(before, bytes())
    }

    @Test
    fun `a corrupt file is set aside and defaults are used`() = runTest {
        listOf("{not json", "[1,2]", """{"theme":"dark"}""", """{"version":"one"}""", """{"version":1,"rows":"x"}""")
            .forEach { content ->
                dir.listDirectoryEntries().forEach(Files::delete)
                Files.writeString(path, content)
                val stored = file().read().getOrThrow()
                assertEquals(Prefs(), stored.value, content)
                val copy = assertIs<StoredOrigin.Recovered>(stored.origin).corruptCopy
                assertEquals("settings.json.corrupt-20261115T102030Z", copy.name)
                assertEquals(content, Files.readString(copy))
                assertTrue(!Files.exists(path), "the corrupt file isn't left in place: $content")
            }
    }

    @Test
    fun `a second corrupt copy at the same moment doesn't overwrite the first`() = runTest {
        Files.writeString(path, "{bad")
        file().read().getOrThrow()
        Files.writeString(path, "{worse")
        val copy = assertIs<StoredOrigin.Recovered>(file().read().getOrThrow().origin).corruptCopy
        assertEquals("settings.json.corrupt-20261115T102030Z-2", copy.name)
        assertEquals("{bad", Files.readString(dir.resolve("settings.json.corrupt-20261115T102030Z")))
    }

    @Test
    fun `writing over a corrupt file sets it aside first`() = runTest {
        Files.writeString(path, "{bad")
        file().write(Prefs("dark")).getOrThrow()
        assertEquals("{bad", Files.readString(dir.resolve("settings.json.corrupt-20261115T102030Z")))
        assertEquals(Prefs("dark"), file().read().getOrThrow().value)
    }

    @Test
    fun `an older version with no migration is refused and left alone`() = runTest {
        Files.writeString(path, """{"version":1,"theme":"dark"}""")
        val before = bytes()
        val error = file(version = 2).read().errorOrNull
        assertEquals("DATA-R-READ-SR", error?.code)
        assertContentEquals(before, bytes())
    }

    @Test
    fun `an interrupted write leaves the old file and no temp file`() = runTest {
        file().write(Prefs("dark")).getOrThrow()
        val before = bytes()
        assertFailsWith<IllegalStateException> {
            AtomicFile.write(path, "{}".toByteArray()) { error("power cut") }
        }
        assertContentEquals(before, bytes())
        assertEquals(listOf("settings.json"), dir.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `a temp file left by a crash doesn't matter`() = runTest {
        file().write(Prefs("dark")).getOrThrow()
        Files.writeString(dir.resolve(".settings.json.123.tmp"), "{half")
        assertEquals(Prefs("dark"), file().read().getOrThrow().value)
        file().write(Prefs("light")).getOrThrow()
        assertEquals(Prefs("light"), file().read().getOrThrow().value)
    }

    @Test
    fun `parent folders are created on the first write`() = runTest {
        val nested =
            VersionedJsonFile(
                dir.resolve("a b/Grâft/settings.json"),
                1,
                Prefs.serializer(),
                { Prefs() },
                StandardTestDispatcher(testScheduler),
            )
        nested.write(Prefs("dark")).getOrThrow()
        assertEquals(Prefs("dark"), nested.read().getOrThrow().value)
    }

    @Test
    fun `parallel writes all land, and the file stays valid`() = runTest {
        val f = file()
        (1..50).map { i -> async { f.write(Prefs(rows = i)) } }.awaitAll().forEach { it.getOrThrow() }
        val stored = f.read().getOrThrow()
        assertEquals(StoredOrigin.Current, stored.origin)
        assertTrue(stored.value.rows in 1..50)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // POSIX permissions
    fun `a folder that can't be written fails with a write code`() = runTest {
        val locked = Files.createDirectory(dir.resolve("locked"))
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            val f =
                VersionedJsonFile(
                    locked.resolve("x.json"),
                    1,
                    Prefs.serializer(),
                    { Prefs() },
                    StandardTestDispatcher(testScheduler),
                )
            assertEquals("DATA-R-WRIT-SW", f.write(Prefs()).errorOrNull?.code)
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun `a version field in the payload is refused`() = runTest {
        val f = VersionedJsonFile(path, 1, Bad.serializer(), { Bad() }, StandardTestDispatcher(testScheduler))
        assertEquals("DATA-R-WRIT-GEN", f.write(Bad()).errorOrNull?.code)
    }
}
