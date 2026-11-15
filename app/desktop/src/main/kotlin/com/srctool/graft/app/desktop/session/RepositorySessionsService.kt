// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.app.desktop.session

import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.presentation.session.RepositorySession
import com.srctool.graft.presentation.session.RepositorySessions
import com.srctool.graft.presentation.window.WindowState
import com.srctool.sinew.presentation.AppService
import com.srctool.sinew.presentation.ServiceLifetime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps one [GitRepositorySession] per repository that some tab shows ([WindowState.openRepositories]): it opens with
 * the repository's first tab and closes with its last.
 *
 * Sinew's `AppServiceHost` runs a fixed list of services, while sessions come and go with tabs, so this App service
 * drives each session's own lifecycle: `init`, then `listen` in a child scope of its own; closing cancels that scope
 * (the watcher and the session's git processes stop) and then calls `destroy`.
 */
internal class RepositorySessionsService(
    private val windows: WindowState,
    private val newSession: (RepositoryLocation) -> GitRepositorySession,
) : AppService(ServiceLifetime.App),
    RepositorySessions {
    private class Running(val session: GitRepositorySession, val job: Job)

    // Changed by the collector, read by windowFocused on the UI thread.
    private val running = ConcurrentHashMap<RepositoryLocation, Running>()
    private val state = MutableStateFlow<Map<RepositoryLocation, RepositorySession>>(emptyMap())
    override val sessions: StateFlow<Map<RepositoryLocation, RepositorySession>> = state.asStateFlow()

    override fun listen(scope: CoroutineScope) {
        scope.launch {
            windows.openRepositories.collect { open ->
                (running.keys - open).forEach { close(it) }
                (open - running.keys).forEach { start(scope, it) }
                state.value = running.mapValues { it.value.session }
            }
        }
    }

    /**
     * A window came to the front: its repositories are read again, since nothing guarantees a watcher saw every change
     * while the app was in the background (a network drive, a sleeping machine).
     */
    fun windowFocused(windowId: String) {
        windows.repositoriesIn(windowId).forEach { running[it]?.session?.refresh() }
    }

    override suspend fun destroy() {
        running.keys.toList().forEach { close(it) }
        state.update { emptyMap() }
    }

    private fun start(scope: CoroutineScope, location: RepositoryLocation) {
        val session = newSession(location)
        val job = SupervisorJob(scope.coroutineContext[Job])
        val sessionScope = CoroutineScope(scope.coroutineContext + job)
        sessionScope.launch {
            session.init()
            session.listen(sessionScope)
        }
        running[location] = Running(session, job)
    }

    private suspend fun close(location: RepositoryLocation) {
        val closing = running.remove(location) ?: return
        withContext(NonCancellable) {
            closing.job.cancelAndJoin()
            closing.session.destroy()
        }
    }
}
