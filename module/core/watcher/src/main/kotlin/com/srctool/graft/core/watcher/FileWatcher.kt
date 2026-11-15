// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.watcher

import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

/**
 * The folders of one repository to watch: [workTree] (null for a bare repository), [gitDir] (`.git`, or
 * `.git/worktrees/<name>` for a linked worktree) and [commonDir] (the shared refs and objects; the same as [gitDir]
 * outside linked worktrees). All three come from `git rev-parse` (Repository data, G1 Task 3).
 */
public data class WatchedRepository(val workTree: Path?, val gitDir: Path, val commonDir: Path = gitDir)

/**
 * What changed in a watched repository, already coalesced (Platform Integration 3.1): a session refreshes what each
 * one names (Architecture, "Refresh").
 */
public sealed interface RepoChange {
    /** `HEAD`, a ref, `packed-refs`, a reflog or the config changed: refresh refs and graph decorations. Sent at once. */
    public data object RefsChanged : RepoChange

    /** The index or the working tree changed: refresh status. Sent 300 ms after the last such event. */
    public data object StatusChanged : RepoChange

    /** A merge, rebase, cherry-pick, revert or bisect started or ended: refresh the operation banner. Sent at once. */
    public data object OperationChanged : RepoChange

    /** Events may have been missed: refresh everything instead of trusting them. */
    public data class FullRefresh(val reason: Reason) : RepoChange {
        public enum class Reason {
            /** The OS dropped events (too many at once). */
            OVERFLOW,

            /** The machine woke from sleep. */
            WAKE,
        }
    }

    /**
     * Native watching couldn't start, so this repository is polled every few seconds instead. The UI says so, and for
     * [Reason.WATCH_LIMIT] on Linux shows how to raise `fs.inotify.max_user_watches`. [detail] is the OS's message.
     */
    public data class PollingFallback(val reason: Reason, val detail: String) : RepoChange {
        public enum class Reason {
            /** The OS limit on watched folders was reached (Linux inotify). */
            WATCH_LIMIT,

            /** The file system doesn't deliver events, or watching failed for another reason. */
            UNSUPPORTED,
        }
    }
}

/**
 * How Graft notices changes made outside it: by the terminal, an IDE or an agent. One watch per repository session
 * (an app service in `:app:desktop`, ADR-26); nothing else uses it.
 */
public interface FileWatcher {
    /**
     * The changes in [repository] while the flow is collected. Watching starts when collection starts and stops when
     * it's cancelled. Never fails: when native watching can't start, it reports [RepoChange.PollingFallback] and polls.
     */
    public fun watch(repository: WatchedRepository): Flow<RepoChange>
}
