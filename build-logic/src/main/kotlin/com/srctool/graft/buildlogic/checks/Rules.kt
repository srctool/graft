// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.buildlogic.checks

/** Allowed project dependencies, read from `config/module-graph.txt` (one `from -> to` per line, `#` comments). */
class ModuleGraph(private val edges: Set<Pair<String, String>>) {
    /** The dependencies of [from] that the graph doesn't allow. */
    fun violations(from: String, dependsOn: Set<String>): List<String> =
        dependsOn.filter { it != from && (from to it) !in edges }.sorted()

    /** Edges the graph allows from [from], for the error message. */
    fun allowedFrom(from: String): List<String> = edges.filter { it.first == from }.map { it.second }.sorted()

    companion object {
        fun parse(text: String): ModuleGraph {
            val edges =
                text.lines().mapIndexedNotNull { index, raw ->
                    val line = raw.substringBefore('#').trim()
                    if (line.isEmpty()) return@mapIndexedNotNull null
                    val parts = line.split("->").map { it.trim() }
                    require(parts.size == 2 && parts.all { it.startsWith(":") && ' ' !in it }) {
                        "module-graph.txt line ${index + 1}: expected \":from -> :to\", got \"$raw\""
                    }
                    parts[0] to parts[1]
                }
            return ModuleGraph(edges.toSet())
        }
    }
}

data class Violation(val file: String, val line: Int, val message: String) {
    override fun toString() = "$file:$line: $message"
}

/** Which Compose packages a module may use (Architecture, ADR-19, ADR-20, ADR-21). */
object ImportRules {
    private val material3 = Regex("""\bandroidx\.compose\.material3\b""")
    private val compose = Regex("""\bandroidx\.compose\b(?!\w)""")

    fun violations(projectPath: String, file: String, text: String): List<Violation> {
        val noCompose = projectPath.startsWith(":core:") || projectPath == ":app:cli"
        val noMaterial = projectPath != ":ui:kit"
        val result = mutableListOf<Violation>()
        var inBlockComment = false
        text.lines().forEachIndexed { index, raw ->
            val code = stripComments(raw, inBlockComment).also { inBlockComment = it.second }.first
            when {
                noCompose && compose.containsMatchIn(code) ->
                    result +=
                        Violation(
                            file,
                            index + 1,
                            "$projectPath must not use androidx.compose (core-* and app-cli stay UI-free)",
                        )

                noMaterial && material3.containsMatchIn(code) ->
                    result +=
                        Violation(
                            file,
                            index + 1,
                            "$projectPath must not use androidx.compose.material3; use ui-kit components",
                        )
            }
        }
        return result
    }

    // Good enough for import and reference checks: drops // line comments and /* */ blocks, ignoring string literals.
    private fun stripComments(line: String, startsInBlock: Boolean): Pair<String, Boolean> {
        val out = StringBuilder()
        var inBlock = startsInBlock
        var i = 0
        while (i < line.length) {
            when {
                inBlock && line.startsWith("*/", i) -> {
                    inBlock = false
                    i += 2
                }

                inBlock -> i++

                line.startsWith("/*", i) -> {
                    inBlock = true
                    i += 2
                }

                line.startsWith("//", i) -> break

                else -> out.append(line[i++])
            }
        }
        return out.toString() to inBlock
    }
}

/** The two-line SPDX header every `.kt` / `.kts` file starts with (Conventions §5). */
object LicenseHeader {
    const val SPDX = "// SPDX-License-Identifier: GPL-3.0-or-later"
    private val copyright = Regex("""// Copyright \(C\) \d{4}(-\d{4})? Graft contributors""")

    const val EXPECTED = "$SPDX\n// Copyright (C) <year> Graft contributors"

    fun isValid(text: String): Boolean {
        val lines = text.lineSequence().take(2).map { it.trimEnd('\r') }.toList()
        return lines.size == 2 && lines[0] == SPDX && copyright.matches(lines[1])
    }
}
