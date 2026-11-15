// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.preference

import com.srctool.graft.core.exception.GraftCodes
import com.srctool.sinew.exception.ExceptionHandler
import com.srctool.sinew.exception.StorageFullException
import com.srctool.sinew.exception.StorageReadException
import com.srctool.sinew.exception.StorageWriteException
import com.srctool.sinew.exception.processCall
import com.srctool.sinew.models.ErrorMessage
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.SinewException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A value read from a [VersionedJsonFile], and where it came from. */
public data class Stored<T>(val value: T, val origin: StoredOrigin) {
    /** Written by a newer Graft: show it, but never write it back (Storage Formats 3.1, rule 3). */
    val readOnly: Boolean get() = origin is StoredOrigin.Newer
}

/** Where a [Stored] value came from. */
public sealed interface StoredOrigin {
    /** No file yet: the defaults. */
    public data object Missing : StoredOrigin

    /** A file in this Graft's version. */
    public data object Current : StoredOrigin

    /**
     * A file from a newer Graft, read with the fields this Graft knows (or the defaults if it can't be decoded). The
     * file is never rewritten.
     */
    public data class Newer(val version: Int) : StoredOrigin

    /** The file was corrupt: it was moved to [corruptCopy] and the defaults are used. */
    public data class Recovered(val corruptCopy: Path) : StoredOrigin
}

/** A write refused because the file was written by a newer Graft (`DATA-R-WRIT-VER`). */
public class StoredFormatTooNewException(
    function: String,
    public val foundVersion: Int,
    public val supportedVersion: Int,
) : SinewException("DATA", ExceptionLayer.Repository, function) {
    override val typeCode: String = GraftCodes.Types.VERSION
    override val errorMessage: ErrorMessage =
        ErrorMessage.Local(
            key = "data.error.newerFormat",
            args = mapOf("found" to foundVersion, "supported" to supportedVersion),
            fallback = "This file was saved by a newer Graft (format {found}). This version only reads it.",
        )
}

/**
 * One JSON file in Graft's own folders, following the Storage Formats rules:
 * - the object starts with `"version"`, checked before anything else;
 * - unknown fields are ignored, missing ones take their defaults (additions stay compatible);
 * - a newer version is read-only, never rewritten;
 * - a corrupt file is moved aside as `<name>.corrupt-<time>` and the defaults are used, never silently overwritten;
 * - writes are atomic ([AtomicFile]) and check the file's version again first.
 *
 * Older versions need a migration, which arrives with the migration runner; until then they're refused untouched
 * (`DATA-R-READ-SR`). The value [T] must encode to a JSON object without its own `version` field.
 */
public class VersionedJsonFile<T>(
    public val path: Path,
    public val version: Int,
    private val serializer: KSerializer<T>,
    private val defaults: () -> T,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Instant = Instant::now,
) {
    init {
        require(version >= 1) { "Format versions start at 1" }
    }

    private val mutex = Mutex()

    /** The stored value, or the defaults when the file is missing or corrupt. */
    public suspend fun read(): Result<Stored<T>> = processCall(MODULE, READ, ExceptionLayer.Repository, readHandlers) {
        mutex.withLock { withContext(dispatcher) { readNow() } }
    }

    /** Replaces the file with [value]; refused when the file was written by a newer Graft. */
    public suspend fun write(value: T): Result<Unit> =
        processCall(MODULE, WRITE, ExceptionLayer.Repository, writeHandlers) {
            mutex.withLock { withContext(dispatcher) { writeNow(value) } }
        }

    private fun readNow(): Stored<T> {
        val bytes =
            try {
                Files.readAllBytes(path)
            } catch (_: NoSuchFileException) {
                return Stored(defaults(), StoredOrigin.Missing)
            }
        return when (val content = parse(bytes)) {
            Content.Corrupt -> Stored(defaults(), StoredOrigin.Recovered(setAside()))

            is Content.Valid -> when {
                content.version == version -> {
                    val value = decode(content.payload) ?: return Stored(defaults(), StoredOrigin.Recovered(setAside()))
                    Stored(value, StoredOrigin.Current)
                }

                content.version > version ->
                    Stored(decode(content.payload) ?: defaults(), StoredOrigin.Newer(content.version))

                else -> throw OlderFormatException(content.version, version)
            }
        }
    }

    private fun writeNow(value: T) {
        if (Files.exists(path)) {
            when (val content = parse(Files.readAllBytes(path))) {
                Content.Corrupt -> setAside()

                is Content.Valid ->
                    if (content.version > version) {
                        throw StoredFormatTooNewException(WRITE, content.version, version)
                    }
            }
        }
        val payload = json.encodeToJsonElement(serializer, value)
        require(payload is JsonObject) { "${path.fileName} must store a JSON object" }
        require(VERSION !in payload) { "${path.fileName}'s value can't have its own \"$VERSION\" field" }
        val document = JsonObject(mapOf(VERSION to JsonPrimitive(version)) + payload)
        AtomicFile.write(path, json.encodeToString(JsonObject.serializer(), document).toByteArray())
    }

    private fun parse(bytes: ByteArray): Content {
        val element =
            try {
                json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
            } catch (_: SerializationException) {
                return Content.Corrupt
            }
        val obj = element as? JsonObject ?: return Content.Corrupt
        val found = (obj[VERSION] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return Content.Corrupt
        if (found < 1) return Content.Corrupt
        return Content.Valid(found, JsonObject(obj - VERSION))
    }

    private fun decode(payload: JsonObject): T? = try {
        json.decodeFromJsonElement(serializer, payload)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Moves the corrupt file to `<name>.corrupt-<time>` (with `-2`, `-3`… if that exists) and returns where. */
    private fun setAside(): Path {
        val stamp = STAMP.format(clock())
        val base = "${path.fileName}.corrupt-$stamp"
        var target = path.resolveSibling(base)
        var n = 2
        while (Files.exists(target)) target = path.resolveSibling("$base-${n++}")
        Files.move(path, target)
        return target
    }

    private sealed interface Content {
        data object Corrupt : Content

        class Valid(val version: Int, val payload: JsonObject) : Content
    }

    private class OlderFormatException(found: Int, supported: Int) :
        IOException("Format $found needs a migration to $supported")

    private companion object {
        const val MODULE = "DATA"
        const val READ = "READ"
        const val WRITE = "WRIT"
        const val VERSION = "version"

        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                prettyPrint = true
            }

        val readHandlers =
            listOf(
                ExceptionHandler({
                    it is IOException
                }) { StorageReadException(MODULE, ExceptionLayer.Repository, READ, it) },
            )

        val writeHandlers =
            listOf(
                ExceptionHandler({ it is IOException && it.isDiskFull() }) {
                    StorageFullException(MODULE, ExceptionLayer.Repository, WRITE, it)
                },
                ExceptionHandler({ it is IOException }) {
                    StorageWriteException(MODULE, ExceptionLayer.Repository, WRITE, it)
                },
            )

        fun Throwable.isDiskFull(): Boolean = generateSequence(this) { it.cause }.any { e ->
            val text = e.message.orEmpty()
            text.contains("No space left", ignoreCase = true) || text.contains("not enough space", ignoreCase = true)
        }
    }
}
