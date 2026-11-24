// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.source.local

import com.srctool.graft.core.git.StreamingGitCommand
import com.srctool.graft.data.git.api.models.GuardFinding
import com.srctool.graft.data.git.api.models.GuardRule
import com.srctool.graft.data.git.api.models.GuardSeverity
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads a unified diff (`-U0`) as git writes it and reports the added lines' secrets and conflict markers. One line
 * is held at a time, so the memory a push needs doesn't grow with the diff. Binary files and removed lines are not
 * looked at (only their names and sizes are checked, elsewhere).
 */
internal object GuardDiffScanner {
    private val hunk = Regex("""^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

    fun scan(input: InputStream, secrets: Boolean, markers: Boolean): Sequence<GuardFinding> = sequence {
        var path: String? = null
        var inHunk = false
        var binary = false
        var line = 0
        val reader = input.buffered()
        while (true) {
            val raw = readLine(reader) ?: break
            val text = String(raw, Charsets.UTF_8)
            if (text.startsWith("diff --git ")) {
                path = null
                inHunk = false
                binary = false
                continue
            }
            if (!inHunk) {
                when {
                    text.startsWith("+++ ") -> path = headerPath(text.substring(4))

                    text.startsWith("Binary files ") || text == "GIT binary patch" -> binary = true

                    text.startsWith("@@ ") -> {
                        val m = hunk.find(text)
                        if (m != null) {
                            inHunk = true
                            line = m.groupValues[1].toInt()
                        }
                    }
                }
                continue
            }
            when {
                text.startsWith("@@ ") -> hunk.find(text)?.let { line = it.groupValues[1].toInt() }

                text.startsWith("+") && path != null && !binary -> {
                    val added = text.substring(1)
                    if (markers && GuardRules.isConflictMarker(added)) {
                        yield(
                            GuardRules.finding(
                                GuardRule.CONFLICT_MARKER,
                                GuardSeverity.BLOCK,
                                path,
                                line,
                                added.take(MARKER_SHOWN),
                                "Conflict marker",
                                "$line:${added.take(MARKER_SHOWN)}",
                            ),
                        )
                    }
                    if (secrets) {
                        for (hit in GuardRules.secrets(added)) {
                            yield(
                                GuardRules.finding(
                                    GuardRule.SECRET,
                                    GuardSeverity.BLOCK,
                                    path,
                                    line,
                                    hit.masked,
                                    hit.label,
                                    hit.value,
                                ),
                            )
                        }
                    }
                    line++
                }

                text.startsWith(" ") -> line++
            }
        }
    }

    private const val MARKER_SHOWN = 7

    /** Reads up to the next `\n` (kept out), or null at the end. */
    private fun readLine(input: java.io.BufferedInputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toByteArray()
            if (b == '\n'.code) return out.toByteArray()
            out.write(b)
        }
    }

    /** `b/dir/name` (the new side), `/dev/null` for a deletion; git quotes odd names and adds a tab after spaces. */
    private fun headerPath(side: String): String? {
        val name = side.removeSuffix("\t").trimEnd('\t')
        if (name == "/dev/null") return null
        val plain = if (name.startsWith("\"") && name.endsWith("\"") && name.length >= 2) unquote(name) else name
        return plain.removePrefix("b/")
    }

    /** Git's C-style quoting: `\\`, `\"`, `\t`, `\n` and octal bytes for the rest. */
    internal fun unquote(quoted: String): String {
        val body = quoted.substring(1, quoted.length - 1)
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\' || i + 1 >= body.length) {
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
                continue
            }
            val n = body[i + 1]
            when {
                n in '0'..'7' -> {
                    var end = i + 1
                    while (end < body.length && end < i + 4 && body[end] in '0'..'7') end++
                    bytes.write(body.substring(i + 1, end).toInt(8))
                    i = end
                }

                else -> {
                    bytes.write(
                        when (n) {
                            't' -> '\t'.code
                            'n' -> '\n'.code
                            else -> n.code
                        },
                    )
                    i += 2
                }
            }
        }
        return bytes.toString(Charsets.UTF_8)
    }
}

/** `git diff` as a stream of findings: the secrets and conflict markers of the added lines. */
internal class GuardPatchCommand(
    args: List<String>,
    environment: Map<String, String?>,
    secrets: Boolean,
    markers: Boolean,
) : StreamingGitCommand<GuardFinding>() {
    override val function = "GARD"
    override val args = args
    override val environment = environment
    private val secretsOn = secrets
    private val markersOn = markers

    override fun parse(stdout: InputStream): Sequence<GuardFinding> =
        GuardDiffScanner.scan(stdout, secretsOn, markersOn)
}
