// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.app.implementation.repositories

import com.srctool.graft.data.app.api.models.Saved
import com.srctool.graft.data.app.api.models.Workspace
import com.srctool.graft.data.app.api.repositories.WorkspaceRepository
import com.srctool.graft.data.app.implementation.source.local.JsonStore
import com.srctool.graft.data.app.implementation.source.local.RepositoryFolders
import com.srctool.graft.data.app.implementation.source.local.WorkspaceRecord
import com.srctool.graft.data.app.implementation.source.local.WorkspacesRecord
import com.srctool.sinew.exception.NotFoundException
import com.srctool.sinew.exception.ValidationException
import com.srctool.sinew.models.ErrorMessage
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

internal class FileWorkspaceRepository(
    private val store: JsonStore<WorkspacesRecord>,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : WorkspaceRepository {
    override suspend fun list(): Result<Saved<List<Workspace>>> =
        store.read().map { saved -> Saved(saved.value.workspaces.map { it.toWorkspace() }, saved.status) }

    override suspend fun create(name: String, repositories: List<Path>): Result<Workspace> {
        val trimmed = validName(name) ?: return invalidName("WSNEW")
        val record = WorkspaceRecord(newId(), trimmed, reals(repositories))
        return store.update { it.copy(workspaces = it.workspaces + record) }.map { record.toWorkspace() }
    }

    override suspend fun rename(id: String, name: String): Result<Workspace> {
        val trimmed = validName(name) ?: return invalidName("WSREN")
        return change(id, "WSREN") { it.copy(name = trimmed) }
    }

    override suspend fun add(id: String, repositories: List<Path>): Result<Workspace> {
        val added = reals(repositories)
        return change(id, "WSADD") { it.copy(repositories = (it.repositories + added).distinct()) }
    }

    override suspend fun removeRepository(id: String, repository: Path): Result<Workspace> {
        val key = reals(listOf(repository)).single()
        return change(id, "WSREM") { w -> w.copy(repositories = w.repositories.filter { it != key }) }
    }

    override suspend fun delete(id: String): Result<Unit> {
        var found = false
        val r =
            store.update { state ->
                found = state.workspaces.any { it.id == id }
                state.copy(workspaces = state.workspaces.filter { it.id != id })
            }
        return when {
            r is Result.Failure -> r
            !found -> notFound(id, "WSDEL")
            else -> Result.Success(Unit)
        }
    }

    private suspend fun change(
        id: String,
        function: String,
        edit: (WorkspaceRecord) -> WorkspaceRecord,
    ): Result<Workspace> {
        var changed: WorkspaceRecord? = null
        val r =
            store.update { state ->
                state.copy(
                    workspaces = state.workspaces.map { w ->
                        if (w.id == id) edit(w).also { changed = it } else w
                    },
                )
            }
        if (r is Result.Failure) return r
        return changed?.let { Result.Success(it.toWorkspace()) } ?: notFound(id, function)
    }

    private suspend fun reals(paths: List<Path>): List<String> =
        withContext(Dispatchers.IO) { paths.map { RepositoryFolders.real(it).toString() }.distinct() }

    private fun validName(name: String): String? = name.trim().takeIf { it.isNotEmpty() && '\n' !in it }

    private fun WorkspaceRecord.toWorkspace() = Workspace(id, name, repositories.map { Paths.get(it) })

    private fun invalidName(function: String): Result.Failure = Result.Failure(
        ValidationException(
            MODULE,
            ExceptionLayer.Repository,
            function,
            mapOf("name" to listOf("empty")),
            ErrorMessage.Local(key = "workspace.error.name", fallback = "Give the workspace a name."),
        ),
    )

    private fun notFound(id: String, function: String): Result.Failure = Result.Failure(
        NotFoundException(
            MODULE,
            ExceptionLayer.Repository,
            function,
            ErrorMessage.Local(
                key = "workspace.error.notFound",
                args = mapOf("id" to id),
                fallback = "That workspace no longer exists.",
            ),
        ),
    )

    private companion object {
        const val MODULE = "DATA"
    }
}
