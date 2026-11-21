// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.CommandLog
import com.srctool.graft.data.git.implementation.repositories.RecordedGitLogRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitLogRepositoryTest {
    private val a = Paths.get("work", "a")
    private val b = Paths.get("work", "b")

    @Test
    fun `a repository sees the commands that ran in it, not another's`() = runBlocking {
        val log = CommandLog()
        log.record(1, a, listOf("status"), 4, 0)
        log.record(2, b, listOf("log"), 9, 0)
        log.record(3, a.resolve("sub"), listOf("diff"), 2, 1)
        val entries = RecordedGitLogRepository(log).entries(a).first()
        assertEquals(listOf("status", "diff"), entries.map { it.command })
    }

    @Test
    fun `a command that failed or never ran is marked failed`() = runBlocking {
        val log = CommandLog()
        log.record(1, a, listOf("push"), 4, 1)
        log.record(2, a, listOf("fetch"), 4, null)
        log.record(3, a, listOf("status"), 4, 0)
        val failed = RecordedGitLogRepository(log).entries(a).first().map { it.failed }
        assertEquals(listOf(true, true, false), failed)
        assertTrue(failed.any())
    }
}
