// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.graft.data.git.implementation.source.local.LogCommand
import com.srctool.graft.data.git.implementation.source.local.RefsCommand
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.Test

/** Re-records `fixtures/git/` from the generated repositories. Runs only with `./gradlew … -PrecordFixtures`. */
@ExtendWith(GitSandboxExtension::class)
class RecordFixturesTest {
    @Test
    fun `record the graph repository`(sandbox: GitSandbox) = runBlocking {
        assumeTrue(Fixtures.recording, "set -PrecordFixtures to re-record")
        val repo = GraphRepo.build(sandbox)
        val runner = sandbox.runner()
        Fixtures.write("log", "graph", runner.run(RawBytesCommand(LogCommand().args), repo).getOrThrow())
        Fixtures.write("refs", "graph", runner.run(RawBytesCommand(RefsCommand().args), repo).getOrThrow())
    }
}
