// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.models

import kotlinx.coroutines.flow.Flow

/**
 * A repository's history as it streams from git: [rows] in topological order, children before parents, and the
 * [people] their author and committer indexes point into. [people] fills up while [rows] is collected, so read a row's
 * people as it arrives or after collection ends.
 */
public class CommitLog(public val rows: Flow<CommitRow>, public val people: People)
