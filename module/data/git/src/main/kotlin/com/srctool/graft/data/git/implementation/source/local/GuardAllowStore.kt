// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.source.local

import com.srctool.graft.data.git.api.models.Allowances
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** What reading an allow-list file gave: its content, and whether it may be rewritten. */
internal class AllowFile<T>(val value: T, val readOnly: Boolean, val unreadable: Boolean = false)

/**
 * `$GIT_DIR/graft/guard-allow.json` v1: the fingerprints the user allowed for good, local to this worktree. This
 * module can't use the versioned JSON helper (it lives in `:module:core:preference`, which this module doesn't depend
 * on), so it follows the same rules by hand: the version first, unknown fields ignored, a newer version read-only,
 * an unreadable file never overwritten, writes atomic.
 */
internal object GuardAllowJson {
    const val VERSION = 1

    class Entry(val fingerprint: String, val rule: String = "", val path: String = "", val time: Long = 0)

    private val json = Json { prettyPrint = true }

    fun file(gitDir: Path): Path = gitDir.resolve("graft").resolve("guard-allow.json")

    fun read(path: Path): AllowFile<List<Entry>> {
        if (!Files.exists(path)) return AllowFile(emptyList(), readOnly = false)
        val unreadable = AllowFile(emptyList<Entry>(), readOnly = true, unreadable = true)
        val element =
            try {
                json.parseToJsonElement(Files.readString(path)) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return unreadable
        val version = (element["version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return unreadable
        val list = element["allow"]
        if (list != null && list !is JsonArray) return unreadable
        val entries =
            (list as JsonArray?).orEmpty().map { item ->
                val obj = item as? JsonObject ?: return unreadable
                val fingerprint = (obj["fingerprint"] as? JsonPrimitive)?.contentOrNull ?: return unreadable
                Entry(
                    fingerprint,
                    (obj["rule"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    (obj["path"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    (obj["time"] as? JsonPrimitive)?.longOrNull ?: 0,
                )
            }
        return AllowFile(entries, readOnly = version > VERSION)
    }

    /** Throws [IOException]; the caller has checked [AllowFile.readOnly]. */
    fun write(path: Path, entries: List<Entry>) {
        val allow =
            JsonArray(
                entries.map {
                    JsonObject(
                        mapOf(
                            "fingerprint" to JsonPrimitive(it.fingerprint),
                            "rule" to JsonPrimitive(it.rule),
                            "path" to JsonPrimitive(it.path),
                            "time" to JsonPrimitive(it.time),
                        ),
                    )
                },
            )
        val document = JsonObject(mapOf("version" to JsonPrimitive(VERSION), "allow" to allow))
        atomicWrite(path, json.encodeToString(JsonObject.serializer(), document))
    }
}

/**
 * `.graft/guard.toml` v1, committed so a team shares its exceptions:
 *
 * ```
 * version = 1
 *
 * [allow]
 * paths = ["fixtures/fake-keys.txt"]
 * fingerprints = ["0123456789abcdef"]
 * ```
 *
 * Only this shape is read: integers, strings, arrays of strings, `#` comments and tables. A TOML library isn't one of
 * the project's, and the file is small and ours.
 */
internal object GuardToml {
    const val VERSION = 1

    fun file(workTree: Path): Path = workTree.resolve(".graft").resolve("guard.toml")

    fun read(path: Path): AllowFile<Allowances> {
        if (!Files.exists(path)) return AllowFile(Allowances(), readOnly = false)
        return try {
            parse(Files.readString(path))
        } catch (_: Exception) {
            AllowFile(Allowances(), readOnly = true, unreadable = true)
        }
    }

    fun parse(text: String): AllowFile<Allowances> {
        var version: Int? = null
        var table = ""
        val arrays = HashMap<String, List<String>>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = stripComment(lines[i]).trim()
            i++
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                table = line.substring(1, line.length - 1).trim()
                continue
            }
            val eq = line.indexOf('=')
            require(eq > 0) { "not a key = value line" }
            val key = line.substring(0, eq).trim()
            var value = line.substring(eq + 1).trim()
            // An array may run over several lines.
            while (value.startsWith("[") && !value.endsWith("]")) {
                require(i < lines.size) { "unterminated array" }
                value += " " + stripComment(lines[i]).trim()
                i++
            }
            when {
                table.isEmpty() && key == "version" -> version = value.toInt()
                table == "allow" && value.startsWith("[") -> arrays[key] = strings(value)
            }
        }
        requireNotNull(version) { "no version" }
        return AllowFile(
            Allowances(arrays["fingerprints"].orEmpty().toSet(), arrays["paths"].orEmpty()),
            readOnly = version > VERSION,
        )
    }

    fun render(allow: Allowances): String = buildString {
        appendLine("version = $VERSION")
        appendLine()
        appendLine("[allow]")
        appendLine("paths = ${array(allow.pathPatterns)}")
        appendLine("fingerprints = ${array(allow.fingerprints.sorted())}")
    }

    fun write(path: Path, allow: Allowances) = atomicWrite(path, render(allow))

    private fun array(items: List<String>): String =
        if (items.isEmpty()) "[]" else items.joinToString(",\n  ", "[\n  ", ",\n]") { quote(it) }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun strings(array: String): List<String> {
        val inner = array.removePrefix("[").removeSuffix("]")
        val out = ArrayList<String>()
        var i = 0
        while (i < inner.length) {
            when (inner[i]) {
                '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (inner[i] != '"') {
                        if (inner[i] == '\\') i++
                        sb.append(inner[i])
                        i++
                    }
                    out += sb.toString()
                    i++
                }

                ',', ' ', '\t' -> i++

                else -> throw IllegalArgumentException("only strings are allowed in these arrays")
            }
        }
        return out
    }

    /** Drops a `#` comment that isn't inside a string. */
    private fun stripComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && inString) {
                i++
            } else if (c == '"') {
                inString = !inString
            } else if (c == '#' && !inString) {
                return line.substring(0, i)
            }
            i++
        }
        return line
    }
}

private fun atomicWrite(path: Path, text: String) {
    Files.createDirectories(path.parent)
    val temp = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
    try {
        Files.writeString(temp, text)
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: IOException) {
        Files.deleteIfExists(temp)
        throw e
    }
}
