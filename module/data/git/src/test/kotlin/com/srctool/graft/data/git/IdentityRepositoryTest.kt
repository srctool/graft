// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.graft.data.git.api.repositories.IdentityRepository
import com.srctool.graft.data.git.di.GitDataModule
import com.srctool.sinew.exception.ValidationException
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The global identity, read and written only in the sandbox's `GIT_CONFIG_GLOBAL`. */
@ExtendWith(GitSandboxExtension::class)
class IdentityRepositoryTest {
    private fun GitSandbox.identity(): IdentityRepository = GitDataModule(runner(), dir("neutral")).identity

    @Test
    fun `an unset identity reads as nothing`(sandbox: GitSandbox) = runBlocking<Unit> {
        val identity = sandbox.identity().read().getOrThrow()
        assertNull(identity.name)
        assertNull(identity.email)
    }

    @Test
    fun `values show the file they come from, included files too`(sandbox: GitSandbox) = runBlocking<Unit> {
        val included = sandbox.home.resolve("work.gitconfig")
        Files.writeString(included, "[user]\n\temail = ann@work.invalid\n")
        Files.writeString(
            sandbox.globalConfig,
            "[user]\n\tname = Ann Lee\n[include]\n\tpath = ${included.toString().replace("\\", "/")}\n",
        )
        val identity = sandbox.identity().read().getOrThrow()
        assertEquals("Ann Lee", identity.name?.value)
        assertEquals(sandbox.globalConfig.toRealPath(), identity.name?.file?.toRealPath())
        assertEquals("ann@work.invalid", identity.email?.value)
        assertEquals(included.toRealPath(), identity.email?.file?.toRealPath())
    }

    @Test
    fun `a write lands in the sandbox's global config, only what changed`(sandbox: GitSandbox) = runBlocking<Unit> {
        val repository = sandbox.identity()
        val first = repository.write("Ann Lee", "ann@example.invalid").getOrThrow()
        assertEquals(listOf("user.name", "user.email"), first.written)
        assertEquals("Ann Lee", first.identity.name?.value)
        val config = Files.readString(sandbox.globalConfig)
        assertTrue("name = Ann Lee" in config && "email = ann@example.invalid" in config, config)

        val second = repository.write("Ann Lee", "ann@other.invalid").getOrThrow()
        assertEquals(listOf("user.email"), second.written)
        assertEquals("ann@other.invalid", sandbox.git(sandbox.home, "config", "--global", "user.email").trim())
    }

    @Test
    fun `nothing is written when nothing changed`(sandbox: GitSandbox) = runBlocking<Unit> {
        Files.writeString(sandbox.globalConfig, "[user]\n\tname = Ann\n\temail = ann@example.invalid\n")
        val before = Files.getLastModifiedTime(sandbox.globalConfig)
        val write = sandbox.identity().write("Ann", "ann@example.invalid").getOrThrow()
        assertEquals(emptyList(), write.written)
        assertEquals(before, Files.getLastModifiedTime(sandbox.globalConfig))
    }

    @Test
    fun `values git would misread are refused before git runs`(sandbox: GitSandbox) = runBlocking<Unit> {
        val repository = sandbox.identity()
        for ((name, email) in listOf("" to "a@b.invalid", "Ann\nEvil" to "a@b.invalid", "Ann" to "--global")) {
            val failure = assertIs<Result.Failure>(repository.write(name, email))
            assertIs<ValidationException>(failure.error)
        }
        assertEquals("", Files.readString(sandbox.globalConfig))
    }
}
