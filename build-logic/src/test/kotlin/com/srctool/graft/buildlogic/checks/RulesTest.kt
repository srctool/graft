// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.buildlogic.checks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModuleGraphTest {
    private val graph =
        ModuleGraph.parse(
            """
            # comment
            :core:git -> :core:model

            :core:repo -> :core:git   # trailing comment
            """.trimIndent(),
        )

    @Test
    fun `allows listed edges`() {
        assertEquals(emptyList(), graph.violations(":core:repo", setOf(":core:git")))
    }

    @Test
    fun `rejects unlisted and transitive edges`() {
        assertEquals(listOf(":core:model"), graph.violations(":core:repo", setOf(":core:git", ":core:model")))
    }

    @Test
    fun `ignores a project depending on itself`() {
        assertEquals(emptyList(), graph.violations(":core:git", setOf(":core:git")))
    }

    @Test
    fun `rejects malformed lines with their line number`() {
        val e = assertFailsWith<IllegalArgumentException> { ModuleGraph.parse(":a -> :b\n:c :d") }
        assertTrue("line 2" in e.message.orEmpty(), e.message)
    }
}

class ImportRulesTest {
    private fun violations(path: String, source: String) = ImportRules.violations(path, "X.kt", source).map { it.line }

    private val uiModules = listOf(":module:core:ui", ":module:feature:graph", ":module:navigation", ":app:desktop")
    private val plainModules =
        listOf(":module:core:git", ":module:data:git", ":module:presentation", ":module:domain", ":app:cli")

    @Test
    fun `material3 is allowed nowhere`() {
        val src = "package x\n\nimport androidx.compose.material3.Text\n"
        (uiModules + plainModules).forEach { assertEquals(listOf(3), violations(it, src), it) }
    }

    @Test
    fun `compose and camouflage only in the UI modules`() {
        listOf(
            "import androidx.compose.runtime.Composable",
            "import com.srctool.camouflage.core.CamoButton",
        ).forEach { line ->
            val src = "package x\n$line\n"
            uiModules.forEach { assertEquals(emptyList(), violations(it, src), "$it: $line") }
            plainModules.forEach { assertEquals(listOf(2), violations(it, src), "$it: $line") }
        }
    }

    @Test
    fun `fully qualified references count too`() {
        assertEquals(
            listOf(2),
            violations(":module:feature:graph", "package x\nval t = androidx.compose.material3.Text\n"),
        )
        assertEquals(
            listOf(2),
            violations(":module:data:git", "package x\nval c = com.srctool.camouflage.core.CamoButton\n"),
        )
    }

    @Test
    fun `comments are ignored`() {
        val src = "// androidx.compose.material3 is not used\n/*\n * androidx.compose.runtime\n */\n"
        assertEquals(emptyList(), violations(":module:core:git", src))
    }

    @Test
    fun `similar package names are not matched`() {
        assertEquals(
            emptyList(),
            violations(":module:core:git", "import androidx.composex.Foo\nimport com.srctool.camouflagex.Foo\n"),
        )
    }
}

class DataModuleSurfaceTest {
    private fun lines(path: String, source: String) = DataModuleSurface.violations(path, "X.kt", source).map { it.line }

    @Test
    fun `public is allowed in api and di packages`() {
        val models = "package com.srctool.graft.data.git.api.models\npublic class A\n"
        assertEquals(emptyList(), lines(":module:data:git", models))
        val di = "package com.srctool.graft.data.git.di\npublic object Wiring\n"
        assertEquals(emptyList(), lines(":module:data:git", di))
    }

    @Test
    fun `public in implementation fails`() {
        val src =
            "package com.srctool.graft.data.git.implementation.source.local\n\n" +
                "public class LogCommand\ninternal class Ok\n"
        assertEquals(listOf(3), lines(":module:data:git", src))
    }

    @Test
    fun `other layers are not checked`() {
        val core = "package com.srctool.graft.core.git\npublic class GitRunner\n"
        assertEquals(emptyList(), lines(":module:core:git", core))
    }
}

class LicenseHeaderTest {
    @Test
    fun `accepts the header with a year or a year range`() {
        assertTrue(
            LicenseHeader.isValid(
                "// SPDX-License-Identifier: GPL-3.0-or-later\n// Copyright (C) 2026 Graft contributors\n",
            ),
        )
        assertTrue(
            LicenseHeader.isValid(
                "// SPDX-License-Identifier: GPL-3.0-or-later\n// Copyright (C) 2026-2027 Graft contributors\n\npackage x\n",
            ),
        )
    }

    @Test
    fun `accepts Windows line endings`() {
        assertTrue(
            LicenseHeader.isValid(
                "// SPDX-License-Identifier: GPL-3.0-or-later\r\n// Copyright (C) 2026 Graft contributors\r\n",
            ),
        )
    }

    @Test
    fun `rejects missing, moved or different headers`() {
        assertTrue(!LicenseHeader.isValid("package x\n"))
        assertTrue(
            !LicenseHeader.isValid(
                "\n// SPDX-License-Identifier: GPL-3.0-or-later\n// Copyright (C) 2026 Graft contributors\n",
            ),
        )
        assertTrue(
            !LicenseHeader.isValid("// SPDX-License-Identifier: MIT\n// Copyright (C) 2026 Graft contributors\n"),
        )
        assertTrue(
            !LicenseHeader.isValid(
                "// SPDX-License-Identifier: GPL-3.0-or-later\n// Copyright (C) Graft contributors\n",
            ),
        )
    }
}
