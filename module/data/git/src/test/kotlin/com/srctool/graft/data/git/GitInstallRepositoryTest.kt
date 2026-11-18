// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.GitRunnerConfig
import com.srctool.graft.core.git.ProcessGitRunner
import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.graft.data.git.api.models.GitInstallStatus
import com.srctool.graft.data.git.di.GitDataModule
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Git check Onboarding's first step runs: a real git, an old one, one that isn't there. */
@ExtendWith(GitSandboxExtension::class)
class GitInstallRepositoryTest {
    private fun module(sandbox: GitSandbox, executable: String) = GitDataModule(
        ProcessGitRunner(
            GitRunnerConfig(executable = executable, environment = sandbox.environment, launcher = sandbox.launcher),
        ),
        sandbox.dir("neutral"),
    )

    @Test
    fun `the git on this machine is ready, with its version`(sandbox: GitSandbox) = runBlocking<Unit> {
        val status = module(sandbox, sandbox.gitExecutable).install.check()
        val ready = assertIs<GitInstallStatus.Ready>(status)
        assertTrue(ready.install.version.matches(Regex("""\d+\.\d+\.\d+""")))
    }

    @Test
    fun `a git older than 2_38 is too old, naming both versions`(sandbox: GitSandbox) = runBlocking<Unit> {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "the fake git is a shell script")
        val script = sandbox.root.resolve("old-git.sh")
        Files.writeString(script, "#!/bin/sh\necho 'git version 2.30.0'\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        val status = module(sandbox, script.toString()).install.check()
        assertEquals(GitInstallStatus.TooOld("2.30.0", "2.38.0"), status)
    }

    @Test
    fun `a git that can't be started is missing`(sandbox: GitSandbox) = runBlocking<Unit> {
        val missing = sandbox.root.resolve("no-such-git").toString()
        assertEquals(GitInstallStatus.Missing, module(sandbox, missing).install.check())
    }
}
