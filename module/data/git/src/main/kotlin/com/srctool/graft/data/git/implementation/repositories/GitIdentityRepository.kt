// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.repositories

import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.data.git.api.models.GlobalIdentity
import com.srctool.graft.data.git.api.models.IdentityWrite
import com.srctool.graft.data.git.api.repositories.IdentityRepository
import com.srctool.graft.data.git.implementation.source.local.GlobalConfigGetCommand
import com.srctool.graft.data.git.implementation.source.local.GlobalConfigSetCommand
import com.srctool.sinew.exception.ValidationException
import com.srctool.sinew.models.ErrorMessage
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.map
import java.nio.file.Path

/**
 * Runs git in [neutralFolder], which belongs to no repository, so a repository's `includeIf` rules don't change what
 * the global identity reads as.
 */
internal class GitIdentityRepository(private val runner: GitRunner, private val neutralFolder: Path) :
    IdentityRepository {
    override suspend fun read(): Result<GlobalIdentity> {
        val name =
            when (val r = runner.run(GlobalConfigGetCommand(NAME), neutralFolder)) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        return runner.run(GlobalConfigGetCommand(EMAIL), neutralFolder).map { GlobalIdentity(name, it) }
    }

    override suspend fun write(name: String, email: String): Result<IdentityWrite> {
        val errors =
            buildMap {
                problem(name)?.let { put("name", listOf(it)) }
                problem(email)?.let { put("email", listOf(it)) }
            }
        if (errors.isNotEmpty()) return invalid(errors)
        val current =
            when (val r = read()) {
                is Result.Success -> r.data
                is Result.Failure -> return r
            }
        val written = ArrayList<String>()
        for ((key, value, now) in listOf(Triple(NAME, name, current.name), Triple(EMAIL, email, current.email))) {
            if (now?.value == value) continue
            when (val r = runner.run(GlobalConfigSetCommand(key, value), neutralFolder)) {
                is Result.Success -> written.add(key)
                is Result.Failure -> return r
            }
        }
        if (written.isEmpty()) return Result.Success(IdentityWrite(written, current))
        return read().map { IdentityWrite(written, it) }
    }

    private fun problem(value: String): String? = when {
        value.isBlank() -> "empty"
        value.any { it == '\n' || it == '\r' || it == '\u0000' } -> "lineBreak"
        value.startsWith('-') -> "leadingDash"
        else -> null
    }

    private fun invalid(errors: Map<String, List<String>>): Result.Failure = Result.Failure(
        ValidationException(
            "GIT",
            ExceptionLayer.Repository,
            "IDNT",
            errors,
            ErrorMessage.Local(
                key = "identity.error.invalid",
                fallback = "Enter a name and an email without line breaks, not starting with \"-\".",
            ),
        ),
    )

    private companion object {
        const val NAME = "user.name"
        const val EMAIL = "user.email"
    }
}
