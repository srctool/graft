// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.domain.implementation.usecases

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertEquals

/** Golden files under the repository's `fixtures/` (Testing Strategy §3.3); `-PrecordFixtures` rewrites them. */
object Golden {
    private val root =
        Paths.get(System.getProperty("graft.fixtures") ?: error("graft.fixtures isn't set; run through Gradle"))
    private val recording = System.getProperty("graft.recordFixtures") == "true"

    /** Compares [actual] with `fixtures/<group>/<case>.txt`. A missing file fails, so new cases are recorded on purpose. */
    fun check(group: String, case: String, actual: String) {
        val file = root.resolve(group).resolve("$case.txt")
        if (recording) {
            Files.createDirectories(file.parent)
            Files.writeString(file, actual)
            return
        }
        check(Files.exists(file)) { "No golden file $file; record it with -PrecordFixtures and review it" }
        assertEquals(Files.readString(file).replace("\r\n", "\n"), actual, "golden file $group/$case.txt")
    }
}
