// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.buildlogic.checks

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains

// Runs the checks in throwaway fixture builds, so a deliberate violation has to fail the real tasks.
class ChecksFunctionalTest {
    @TempDir
    lateinit var dir: File

    private val header = "// SPDX-License-Identifier: GPL-3.0-or-later\n// Copyright (C) 2026 Graft contributors\n"

    private fun write(path: String, text: String) = dir.resolve(path).apply { parentFile.mkdirs() }.writeText(text)

    // Two modules, `:core:a` and `:feature:b`, with only `:feature:b -> :core:a` allowed.
    private fun fixture(bDependsOn: String = ":core:a", aDependsOn: String? = null) {
        write("settings.gradle.kts", header + "rootProject.name = \"fixture\"\ninclude(\":core:a\", \":feature:b\")\n")
        write("build.gradle.kts", header + "plugins { id(\"graft.license-headers\") }\n")
        write("config/module-graph.txt", "# allowed edges\n:feature:b -> :core:a\n")
        write("core/a/build.gradle.kts", header + module(aDependsOn))
        write("feature/b/build.gradle.kts", header + module(bDependsOn))
        write("core/a/src/main/kotlin/A.kt", header + "package a\n")
        write("feature/b/src/main/kotlin/B.kt", header + "package b\n")
    }

    private fun module(dependsOn: String?) = "plugins {\n    `java-library`\n    id(\"graft.checks\")\n}\n" +
        (dependsOn?.let { "dependencies { implementation(project(\"$it\")) }\n" } ?: "")

    private fun runner(vararg tasks: String) = GradleRunner
        .create()
        .withProjectDir(dir)
        .withPluginClasspath()
        .withArguments(*tasks, "--configuration-cache", "--stacktrace")

    private fun pass(vararg tasks: String): BuildResult = runner(*tasks).build()

    private fun fail(vararg tasks: String): BuildResult = runner(*tasks).buildAndFail()

    @Test
    fun `a clean fixture passes check`() {
        fixture()
        pass("check")
        // Second run reuses the configuration cache and stays green.
        pass("check")
    }

    @Test
    fun `an edge missing from module-graph txt fails`() {
        fixture(aDependsOn = ":feature:b")
        val out = fail("checkModuleGraph").output
        assertContains(out, ":core:a -> :feature:b")
        assertContains(out, "config/module-graph.txt")
    }

    @Test
    fun `material3 outside ui-kit fails`() {
        fixture()
        write("feature/b/src/main/kotlin/B.kt", header + "package b\n\nimport androidx.compose.material3.Text\n")
        val out = fail("checkImports").output
        assertContains(out, "B.kt:5")
        assertContains(out, "androidx.compose.material3")
    }

    @Test
    fun `compose in a core module fails`() {
        fixture()
        write("core/a/src/test/kotlin/ATest.kt", header + "package a\n\nimport androidx.compose.runtime.Composable\n")
        val out = fail("check").output
        assertContains(out, "ATest.kt:5")
    }

    @Test
    fun `a missing license header fails`() {
        fixture()
        write("feature/b/src/main/kotlin/NoHeader.kt", "package b\n")
        val out = fail("check").output
        assertContains(out, "NoHeader.kt")
        assertContains(out, "SPDX-License-Identifier")
    }

    @Test
    fun `build output is not checked for headers`() {
        fixture()
        write("feature/b/build/generated/Gen.kt", "package b\n")
        pass("checkLicenseHeaders")
    }
}
