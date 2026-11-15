// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git.testing

import com.srctool.graft.core.git.GitCommand
import com.srctool.graft.core.git.GitException
import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.core.git.GitRunnerConfig
import com.srctool.graft.core.git.ProcessGitRunner
import com.srctool.graft.core.git.ProcessLauncher
import com.srctool.sinew.models.Result
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A throwaway environment for every test that runs git (Testing Strategy §3.1): temp `HOME`, `XDG_CONFIG_HOME`,
 * `APPDATA` and global git config, no system config, no SSH agent, fixed identity and dates. Every process a
 * [runner] starts must run inside [root], or the test fails.
 *
 * Get one with [GitSandboxExtension]. The git executable is `git` on `PATH`, or the `graft.test.git` system
 * property (CI's `git-min` job sets it to Git 2.38 with `-Pgit=…`).
 */
public class GitSandbox(tempDir: Path) {
    /** The sandbox folder with symlinks resolved: macOS temp folders live behind `/var -> /private/var`. */
    public val root: Path = tempDir.toRealPath()
    public val home: Path = dir("home")
    public val globalConfig: Path = home.resolve(".gitconfig").also { if (Files.notExists(it)) Files.createFile(it) }
    public val gitExecutable: String = System.getProperty("graft.test.git")?.takeIf { it.isNotBlank() } ?: "git"

    /** The variables every sandboxed process gets on top of the inherited environment (null removes one). */
    public val environment: Map<String, String?> =
        buildMap {
            // Nothing from the developer's shell may steer git: GIT_DIR from a hook, an SSH agent, a global config.
            System.getenv().keys.filter { it.startsWith("GIT_") || it.startsWith("SSH_") }.forEach { put(it, null) }
            put("HOME", home.toString())
            put("USERPROFILE", home.toString())
            put("XDG_CONFIG_HOME", dir("xdg-config").toString())
            put("APPDATA", dir("appdata").toString())
            put("LOCALAPPDATA", dir("localappdata").toString())
            put("GIT_CONFIG_GLOBAL", globalConfig.toString())
            put("GIT_CONFIG_NOSYSTEM", "1")
            put("GIT_AUTHOR_NAME", AUTHOR_NAME)
            put("GIT_AUTHOR_EMAIL", AUTHOR_EMAIL)
            put("GIT_AUTHOR_DATE", DATE)
            put("GIT_COMMITTER_NAME", AUTHOR_NAME)
            put("GIT_COMMITTER_EMAIL", AUTHOR_EMAIL)
            put("GIT_COMMITTER_DATE", DATE)
        }

    /** Processes that tried to start outside [root]. [GitSandboxExtension] fails the test when this isn't empty. */
    public val violations: MutableList<String> = CopyOnWriteArrayList()

    /**
     * Starts processes only inside [root]. A start outside it throws, which the runner's guard turns into a failed
     * `Result`, so it's also recorded in [violations] for the extension to fail the test even if the result is ignored.
     */
    public val launcher: ProcessLauncher =
        ProcessLauncher { argv, cwd, env ->
            val real = cwd.toRealPath()
            if (!real.startsWith(root)) {
                val message = "A process tried to start outside the sandbox: $real"
                violations += message
                throw AssertionError(message)
            }
            ProcessLauncher.Default.start(argv, cwd, env)
        }

    /** A runner confined to the sandbox, using [executable] (default: [gitExecutable]). */
    public fun runner(executable: String = gitExecutable): GitRunner =
        ProcessGitRunner(GitRunnerConfig(executable = executable, environment = environment, launcher = launcher))

    /** A folder inside the sandbox, created if missing. */
    public fun dir(name: String): Path = Files.createDirectories(root.resolve(name))

    /** Runs `git <args>` in [cwd] and returns stdout; fails the test if git fails. For setting up repositories. */
    public fun git(cwd: Path, vararg args: String): String =
        when (val result = runBlocking { runner().run(RawGitCommand(args.toList()), cwd) }) {
            is Result.Success -> result.data

            is Result.Failure -> throw AssertionError(
                "git ${args.joinToString(
                    " ",
                )} failed: ${result.error.code} ${(result.error as? GitException)?.stderr.orEmpty()}",
                result.error,
            )
        }

    /** Creates a repository in `work/<name>` on branch `main` and builds its history with [build]. */
    public fun repo(name: String = "repo", build: RepoBuilder.() -> Unit = {}): Path {
        val path = dir("work").resolve(name)
        git(dir("work"), "init", "--quiet", "--initial-branch=main", path.toString())
        RepoBuilder(this, path).build()
        return path
    }

    /**
     * A fake `git` (POSIX only): a shell script with [body], for runner tests that need git to misbehave. Returns its
     * path, to pass to [runner].
     */
    public fun fakeGit(body: String, name: String = "git"): String {
        val file = dir("bin").resolve(name)
        Files.writeString(file, "#!/bin/sh\n$body\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"))
        return file.toString()
    }

    public companion object {
        public const val AUTHOR_NAME: String = "Graft Test"
        public const val AUTHOR_EMAIL: String = "test@graft.invalid"
        public const val DATE: String = "2026-01-01T00:00:00+0000"

        public val isPosix: Boolean = !System.getProperty("os.name").startsWith("Windows")
    }
}

/** `git <args>` returning stdout as text. Test setup only; production code uses typed commands. */
public class RawGitCommand(override val args: List<String>, override val mutates: Boolean = true) :
    GitCommand<String>() {
    override val function: String = "TEST"

    override fun parse(stdout: ByteArray): String = stdout.toString(Charsets.UTF_8)
}
