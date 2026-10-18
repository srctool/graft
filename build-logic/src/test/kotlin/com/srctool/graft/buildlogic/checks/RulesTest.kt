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

    @Test
    fun `material3 is allowed only in ui-kit`() {
        val src = "package x\n\nimport androidx.compose.material3.Text\n"
        assertEquals(emptyList(), violations(":ui:kit", src))
        assertEquals(listOf(3), violations(":feature:graph", src))
        assertEquals(listOf(3), violations(":app:desktop", src))
    }

    @Test
    fun `fully qualified material3 references count too`() {
        assertEquals(listOf(2), violations(":feature:graph", "package x\nval t = androidx.compose.material3.Text\n"))
    }

    @Test
    fun `core and app-cli never import compose`() {
        val src = "package x\nimport androidx.compose.runtime.Composable\n"
        assertEquals(listOf(2), violations(":core:git", src))
        assertEquals(listOf(2), violations(":app:cli", src))
        assertEquals(emptyList(), violations(":feature:graph", src))
    }

    @Test
    fun `comments are ignored`() {
        val src = "// androidx.compose.material3 is only for ui-kit\n/*\n * androidx.compose.runtime\n */\n"
        assertEquals(emptyList(), violations(":core:git", src))
    }

    @Test
    fun `similar package names are not matched`() {
        assertEquals(emptyList(), violations(":core:git", "import androidx.composex.Foo\n"))
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
