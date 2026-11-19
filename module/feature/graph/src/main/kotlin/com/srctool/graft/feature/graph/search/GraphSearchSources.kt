// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.graph.search

import com.srctool.graft.core.ui.l10n.GraftLocalizer
import com.srctool.graft.data.git.api.models.LogQuery
import com.srctool.graft.data.git.api.models.LogScope
import com.srctool.graft.data.git.api.models.RefKind
import com.srctool.graft.data.git.api.models.RepositoryLocation
import com.srctool.graft.data.git.api.repositories.CommitRepository
import com.srctool.graft.data.git.api.repositories.TreeRepository
import com.srctool.graft.presentation.search.SearchCandidate
import com.srctool.graft.presentation.search.SearchGroup
import com.srctool.graft.presentation.search.SearchKind
import com.srctool.graft.presentation.search.SearchScope
import com.srctool.graft.presentation.search.SearchSource
import com.srctool.graft.presentation.search.SearchTarget
import com.srctool.sinew.models.Result
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

/** The folder git runs in for a repository: its working tree, or the git dir of a bare one. */
private val RepositoryLocation.folder: Path get() = workTree ?: gitDir

/** Branches, remote branches and tags of the open repository, from the session's refs (no git call). */
internal class RefSearchSource : SearchSource {
    override val group: SearchGroup = SearchGroup.Refs
    override val needsRepository: Boolean = true

    override suspend fun candidates(query: String, scope: SearchScope): List<SearchCandidate> {
        val repository = scope.repository ?: return emptyList()
        if (query.isBlank()) return emptyList()
        return repository.session.snapshot.value.refs.mapNotNull { ref ->
            val kind =
                when (ref.kind) {
                    RefKind.LOCAL_BRANCH, RefKind.REMOTE_BRANCH -> SearchKind.Branch
                    RefKind.TAG -> SearchKind.Tag
                    else -> return@mapNotNull null
                }
            SearchCandidate(
                key = "ref:${ref.name}",
                kind = kind,
                title = ref.shortName,
                target = SearchTarget.SelectCommit(ref.commit),
                detail = ref.upstream?.removePrefix(RefKind.REMOTE_BRANCH.prefix),
            )
        }
    }
}

/** Commits of the open repository by message, or by SHA from four hex digits; asks git, so it waits for a pause. */
internal class CommitSearchSource(private val commits: CommitRepository) : SearchSource {
    override val group: SearchGroup = SearchGroup.Commits
    override val needsRepository: Boolean = true
    override val slow: Boolean = true

    override suspend fun candidates(query: String, scope: SearchScope): List<SearchCandidate> {
        val repository = scope.repository ?: return emptyList()
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        val folder = repository.session.location.folder
        val found = LinkedHashMap<String, SearchCandidate>()
        if (HEX.matches(text)) {
            val id = (commits.resolve(folder, text) as? Result.Success)?.data
            if (id != null) {
                (commits.details(folder, id) as? Result.Success)?.data?.let { d ->
                    found[id.hex] =
                        candidate(id.hex, d.message.lineSequence().first(), d.author.name, d.author.time)
                }
            }
        }
        val log = commits.search(folder, LogQuery(message = text), LogScope.All())
        for (row in log.rows.take(LIMIT).toList()) {
            found.getOrPut(row.id.hex) {
                candidate(row.id.hex, row.subject, log.people[row.authorIndex].name, row.authorTime)
            }
        }
        return found.values.toList()
    }

    private fun candidate(hex: String, subject: String, author: String, seconds: Long): SearchCandidate {
        val day = Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()).toLocalDate()
        return SearchCandidate(
            key = "commit:$hex",
            kind = SearchKind.Commit,
            title = subject.ifBlank { hex.take(7) },
            target = SearchTarget.SelectCommit(com.srctool.graft.data.git.api.models.ObjectId(hex)),
            detail = "${hex.take(7)} · $author · $day",
            extra = hex,
            recency = seconds,
        )
    }

    private companion object {
        val HEX = Regex("[0-9a-fA-F]{4,40}")
        const val LIMIT = 30
    }
}

/**
 * Files in HEAD's tree. The list is read once per HEAD and kept; a search keeps the paths that hold every word's
 * letters in order and passes the host the shortest [CAP] of them to rank.
 */
internal class FileSearchSource(
    private val trees: TreeRepository,
    private val l10n: GraftLocalizer = GraftLocalizer(),
) : SearchSource {
    override val group: SearchGroup = SearchGroup.Files
    override val needsRepository: Boolean = true
    override val slow: Boolean = true

    private var cachedFor: String? = null
    private var cached: List<String> = emptyList()
    private var truncated = false

    override suspend fun candidates(query: String, scope: SearchScope): List<SearchCandidate> {
        val repository = scope.repository ?: return emptyList()
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val files = load(repository.session.location, repository.session.snapshot.value.head.toString())
        val inHead = l10n.resolve("search.file.inHead", emptyMap()) ?: "in HEAD"
        return files
            .filter { path -> words.all { inOrder(it, path.lowercase()) } }
            .sortedBy { it.length }
            .take(CAP)
            .map {
                SearchCandidate(
                    key = "file:$it",
                    kind = SearchKind.File,
                    title = it,
                    target = SearchTarget.FileInHead(it),
                    detail = inHead,
                )
            }
    }

    override fun note(): String? = if (truncated) {
        l10n.resolve("search.files.truncated", mapOf("count" to TreeRepository.DEFAULT_LIMIT))
            ?: "Showing the first ${TreeRepository.DEFAULT_LIMIT} files."
    } else {
        null
    }

    private suspend fun load(location: RepositoryLocation, head: String): List<String> {
        val key = "${location.commonDir}|$head"
        if (cachedFor == key) return cached
        when (val r = trees.files(location.folder)) {
            is Result.Success -> {
                cached = r.data.paths
                truncated = r.data.truncated
                cachedFor = key
            }

            is Result.Failure -> throw r.error
        }
        return cached
    }

    private fun inOrder(word: String, lower: String): Boolean {
        var from = 0
        for (c in word) {
            val i = lower.indexOf(c, from)
            if (i < 0) return false
            from = i + 1
        }
        return true
    }

    private companion object {
        const val CAP = 2_000
    }
}
