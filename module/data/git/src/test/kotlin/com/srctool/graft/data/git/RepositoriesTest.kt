// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.GitNotFoundException
import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.graft.data.git.api.models.People
import com.srctool.graft.data.git.di.GitDataModule
import com.srctool.graft.data.git.implementation.source.local.LogParser
import com.srctool.graft.data.git.implementation.source.local.RefsParser
import com.srctool.sinew.models.errorOrNull
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The repositories, wired through [GitDataModule], against a live repository. Their results must equal the recorded
 * fixtures: CI runs this on Git 2.38 and the latest Git, so a format change in either shows up here.
 */
@ExtendWith(GitSandboxExtension::class)
class RepositoriesTest {
    @Test
    fun `the commit log streams the same rows as the fixture`(sandbox: GitSandbox) = runBlocking {
        val repo = GraphRepo.build(sandbox)
        val log = GitDataModule(sandbox.runner()).commits.log(repo)
        val live = log.rows.toList()
        val recordedPeople = People()
        val recorded = LogParser.parse(Fixtures.read("log", "graph").inputStream(), recordedPeople).toList()
        assertEquals(recorded, live)
        assertEquals(
            (0 until recordedPeople.size).map(recordedPeople::get),
            (0 until log.people.size).map(log.people::get),
        )
    }

    @Test
    fun `the refs equal the fixture`(sandbox: GitSandbox) = runBlocking {
        val repo = GraphRepo.build(sandbox)
        assertEquals(
            RefsParser.parse(Fixtures.read("refs", "graph")),
            GitDataModule(sandbox.runner()).refs.refs(repo).getOrThrow(),
        )
    }

    @Test
    fun `a log stops when collection stops`(sandbox: GitSandbox) = runBlocking {
        val repo = GraphRepo.build(sandbox)
        assertEquals(3, GitDataModule(sandbox.runner()).commits.log(repo).rows.take(3).toList().size)
    }

    @Test
    fun `outside a repository both fail with NF`(sandbox: GitSandbox) = runBlocking {
        val data = GitDataModule(sandbox.runner())
        val empty = sandbox.dir("empty")
        assertEquals("GIT-R-REFS-NF", data.refs.refs(empty).errorOrNull?.code)
        val e = assertFailsWith<GitNotFoundException> { data.commits.log(empty).rows.toList() }
        assertEquals("GIT-R-LOG-NF", e.code)
    }
}
