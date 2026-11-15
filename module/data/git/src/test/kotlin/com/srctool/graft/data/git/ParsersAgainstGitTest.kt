// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.graft.data.git.api.models.People
import com.srctool.graft.data.git.implementation.source.local.LogCommand
import com.srctool.graft.data.git.implementation.source.local.LogParser
import com.srctool.graft.data.git.implementation.source.local.RefsCommand
import com.srctool.graft.data.git.implementation.source.local.RefsParser
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The recorded fixtures match what the installed git prints for the same repository today: CI runs this on Git 2.38
 * and the latest Git, so a format change in either shows up here.
 */
@ExtendWith(GitSandboxExtension::class)
class ParsersAgainstGitTest {
    @Test
    fun `git log streams the same rows as the fixture`(sandbox: GitSandbox) = runBlocking {
        val repo = GraphRepo.build(sandbox)
        val live = LogCommand()
        val liveRows = sandbox.runner().stream(live, repo).toList()
        val recordedPeople = People()
        val recorded = LogParser.parse(Fixtures.read("log", "graph").inputStream(), recordedPeople).toList()
        assertEquals(recorded, liveRows)
        assertEquals(
            (0 until recordedPeople.size).map(recordedPeople::get),
            (0 until live.people.size).map(live.people::get),
        )
    }

    @Test
    fun `for-each-ref returns the same refs as the fixture`(sandbox: GitSandbox) = runBlocking {
        val repo = GraphRepo.build(sandbox)
        assertEquals(
            RefsParser.parse(Fixtures.read("refs", "graph")),
            sandbox.runner().run(RefsCommand(), repo).getOrThrow(),
        )
    }
}
