// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.repositories

import com.srctool.graft.core.git.GitExitException
import com.srctool.graft.core.git.GitPatchException
import com.srctool.graft.core.git.GitRunner
import com.srctool.graft.core.git.PatchProblem
import com.srctool.graft.core.git.TempIndex
import com.srctool.graft.data.git.api.models.ChangedFile
import com.srctool.graft.data.git.api.models.DiffTarget
import com.srctool.graft.data.git.api.models.FileSelection.Scope
import com.srctool.graft.data.git.api.models.FileVersion
import com.srctool.graft.data.git.api.models.LocalHistoryLimits
import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.Snapshot
import com.srctool.graft.data.git.api.models.SnapshotHit
import com.srctool.graft.data.git.api.models.SnapshotResult
import com.srctool.graft.data.git.api.models.Thinning
import com.srctool.graft.data.git.api.repositories.LocalHistoryRepository
import com.srctool.graft.data.git.implementation.source.local.ApplyPatchCommand
import com.srctool.graft.data.git.implementation.source.local.ApplyTo
import com.srctool.graft.data.git.implementation.source.local.ChangedFilesCommand
import com.srctool.graft.data.git.implementation.source.local.CommitTreeCommand
import com.srctool.graft.data.git.implementation.source.local.DeleteRefCommand
import com.srctool.graft.data.git.implementation.source.local.FastImportCommand
import com.srctool.graft.data.git.implementation.source.local.FileVersionsCommand
import com.srctool.graft.data.git.implementation.source.local.ForgetPathCommand
import com.srctool.graft.data.git.implementation.source.local.HistoryDiskUsageCommand
import com.srctool.graft.data.git.implementation.source.local.HistoryLocation
import com.srctool.graft.data.git.implementation.source.local.HistoryRefsCommand
import com.srctool.graft.data.git.implementation.source.local.LocateHistoryCommand
import com.srctool.graft.data.git.implementation.source.local.MissingPathsCommand
import com.srctool.graft.data.git.implementation.source.local.PatchBuilder
import com.srctool.graft.data.git.implementation.source.local.RawPatchParser
import com.srctool.graft.data.git.implementation.source.local.ReadTreeCommand
import com.srctool.graft.data.git.implementation.source.local.ResolveCommand
import com.srctool.graft.data.git.implementation.source.local.RestoreFromCommand
import com.srctool.graft.data.git.implementation.source.local.SelectionOutOfRange
import com.srctool.graft.data.git.implementation.source.local.SnapshotCandidatesCommand
import com.srctool.graft.data.git.implementation.source.local.SnapshotDiffCommand
import com.srctool.graft.data.git.implementation.source.local.SnapshotLogCommand
import com.srctool.graft.data.git.implementation.source.local.SnapshotPathsCommand
import com.srctool.graft.data.git.implementation.source.local.UpdateIndexCommand
import com.srctool.graft.data.git.implementation.source.local.UpdateRefCommand
import com.srctool.graft.data.git.implementation.source.local.WriteTreeCommand
import com.srctool.graft.data.git.implementation.source.local.pathspecInput
import com.srctool.sinew.models.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal class GitLocalHistoryRepository(
    private val runner: GitRunner,
    private val limits: LocalHistoryLimits = LocalHistoryLimits(),
    /** Seconds since 1970; a test passes its own clock. */
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : LocalHistoryRepository {
    // --- taking snapshots ---------------------------------------------------------------------------------------

    override suspend fun snapshot(repository: Path, label: String?): Result<SnapshotResult> {
        val where = locate(repository).orReturn { return it }
        val text = label?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf { it.isNotEmpty() }
        return withTree(repository, where) { tree ->
            val head = runner.run(ResolveCommand(where.ref), repository).orReturn { return@withTree it }
            val last = head?.let { latest(repository, where) }?.orReturn { return@withTree it }
            if (last != null && last.tree == tree && text == null) {
                return@withTree Result.Success(SnapshotResult(last, created = false))
            }
            val time = now()
            val message = buildString {
                append(text ?: "Local history snapshot").append("\n\n")
                append("Graft-History-Version: 1\n")
                if (text != null) append(SnapshotLogCommand.LABEL_TRAILER).append(text).append('\n')
            }
            val id =
                runner.run(CommitTreeCommand(tree, last?.id, time), repository, message.toByteArray())
                    .orReturn { return@withTree it }
            runner.run(UpdateRefCommand(where.ref, id, last?.id), repository).orReturn { return@withTree it }
            Result.Success(
                SnapshotResult(Snapshot(id, last?.id, time, text, tree, message.trimEnd('\n')), created = true),
            )
        }
    }

    /**
     * The tree of the working tree as it is now, with [block] to use it. The user's index is copied (so its file
     * stamps save hashing every file again) and only the files that differ from it are added, left out when over the
     * size limit. Ignored files are never candidates; the user's own index is only read.
     */
    private suspend fun <T> withTree(
        repository: Path,
        where: HistoryLocation,
        block: suspend (ObjectId) -> Result<T>,
    ): Result<T> {
        val candidates = runner.run(SnapshotCandidatesCommand(), repository).orReturn { return it }
        val usable = withContext(Dispatchers.IO) { candidates.filter { fits(repository, it) } }
        return TempIndex.scope(where.gitDir) { index ->
            val copied = copyIndex(where.gitDir.resolve("index"), index.file)
            if (copied is Result.Failure) return@scope copied
            if (usable.isNotEmpty()) {
                runner.run(UpdateIndexCommand(index.environment), repository, pathspecInput(usable))
                    .orReturn { return@scope it }
            }
            val tree = runner.run(WriteTreeCommand(index.environment), repository).orReturn { return@scope it }
            block(tree)
        }
    }

    /** A directory (a nested repository) can't be a file entry; a file over the limit is skipped; a link is its path. */
    private fun fits(repository: Path, path: String): Boolean {
        if (path.endsWith("/")) return false
        val file = repository.resolve(path)
        return try {
            val attributes = Files.readAttributes(
                file,
                java.nio.file.attribute.BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            attributes.isSymbolicLink || attributes.size() <= limits.maxFileBytes
        } catch (_: IOException) {
            true // gone: removing it from the copy is what the snapshot should do
        }
    }

    private suspend fun copyIndex(from: Path, to: Path): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (Files.exists(from)) Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
            Result.Success(Unit)
        } catch (e: IOException) {
            Result.Failure(GitExitException("LHST", listOf("copy index"), null, e.message.orEmpty()))
        }
    }

    // --- reading ------------------------------------------------------------------------------------------------

    override suspend fun snapshots(repository: Path, limit: Int): Result<List<Snapshot>> {
        val where = locate(repository).orReturn { return it }
        return list(repository, where, limit)
    }

    private suspend fun list(repository: Path, where: HistoryLocation, limit: Int): Result<List<Snapshot>> {
        if (runner.run(ResolveCommand(where.ref), repository).orReturn { return it } == null) {
            return Result.Success(emptyList())
        }
        return runner.run(SnapshotLogCommand(where.ref, limit), repository)
    }

    private suspend fun latest(repository: Path, where: HistoryLocation): Result<Snapshot?> =
        when (val r = list(repository, where, 1)) {
            is Result.Success -> Result.Success(r.data.firstOrNull())
            is Result.Failure -> r
        }

    override suspend fun changes(repository: Path, snapshot: ObjectId): Result<List<ChangedFile>> =
        runner.run(ChangedFilesCommand(DiffTarget.Commit(snapshot)), repository)

    override suspend fun versions(repository: Path, path: String): Result<List<FileVersion>> {
        val where = locate(repository).orReturn { return it }
        val all = list(repository, where, Int.MAX_VALUE).orReturn { return it }.associateBy { it.id }
        val raw = runner.run(FileVersionsCommand(where.ref, path), repository).orReturn { return it }
        return Result.Success(raw.mapNotNull { (id, blob) -> all[id]?.let { FileVersion(it, blob) } })
    }

    override suspend fun deletedSince(repository: Path, snapshot: ObjectId): Result<List<String>> {
        val where = locate(repository).orReturn { return it }
        return withTree(repository, where) { current ->
            val old = runner.run(ResolveCommand("${snapshot.hex}^{tree}"), repository).orReturn { return@withTree it }
                ?: return@withTree Result.Failure(missing(snapshot))
            runner.run(MissingPathsCommand(old, current, "D"), repository)
        }
    }

    override suspend fun searchPaths(repository: Path, query: String): Result<List<SnapshotHit>> {
        val where = locate(repository).orReturn { return it }
        if (runner.run(ResolveCommand(where.ref), repository).orReturn { return it } == null) {
            return Result.Success(emptyList())
        }
        val snapshots = list(repository, where, Int.MAX_VALUE).orReturn { return it }.associateBy { it.id }
        val found = runner.run(SnapshotPathsCommand(where.ref, null), repository).orReturn { return it }
        return Result.Success(
            found.mapNotNull { (id, paths) ->
                val matching = paths.filter { it.contains(query, ignoreCase = true) }
                snapshots[id]?.takeIf { matching.isNotEmpty() }?.let { SnapshotHit(it, matching) }
            },
        )
    }

    override suspend fun searchText(repository: Path, query: String): Result<List<SnapshotHit>> {
        val where = locate(repository).orReturn { return it }
        if (query.isEmpty() || runner.run(ResolveCommand(where.ref), repository).orReturn { return it } == null) {
            return Result.Success(emptyList())
        }
        val snapshots = list(repository, where, Int.MAX_VALUE).orReturn { return it }.associateBy { it.id }
        val found = runner.run(SnapshotPathsCommand(where.ref, query), repository).orReturn { return it }
        return Result.Success(found.mapNotNull { (id, paths) -> snapshots[id]?.let { SnapshotHit(it, paths) } })
    }

    // --- restoring ----------------------------------------------------------------------------------------------

    override suspend fun restoreFile(repository: Path, snapshot: ObjectId, path: String): Result<Unit> =
        runner.run(RestoreFromCommand(snapshot), repository, pathspecInput(listOf(path)))

    override suspend fun restoreHunks(
        repository: Path,
        snapshot: ObjectId,
        path: String,
        hunks: Set<Int>,
        contextLines: Int,
    ): Result<Unit> {
        val diff = runner.run(SnapshotDiffCommand(snapshot, path, contextLines), repository).orReturn { return it }
        val section = RawPatchParser.parse(diff).firstOrNull() ?: return Result.Failure(refused("$path has no changes"))
        val patch =
            try {
                PatchBuilder.build(path, section, Scope.Hunks(hunks), ApplyTo.NEW)
            } catch (e: SelectionOutOfRange) {
                return Result.Failure(refused(e.message.orEmpty()))
            } ?: return Result.Success(Unit)
        return runner.run(ApplyPatchCommand(cached = false, reverse = true), repository, patch)
    }

    override suspend fun restoreAll(repository: Path, snapshot: ObjectId): Result<SnapshotResult> {
        // The safety net first: if this fails, nothing has been touched.
        val before = snapshot(repository, "before restore").orReturn { return it }
        val target = runner.run(ResolveCommand("${snapshot.hex}^{tree}"), repository).orReturn { return it }
            ?: return Result.Failure(missing(snapshot))
        val added = runner.run(MissingPathsCommand(target, before.snapshot.tree, "A"), repository).orReturn {
            return it
        }
        // Everything the snapshot has goes back, then what was made since goes away.
        val all = runner.run(MissingPathsCommand(before.snapshot.tree, target, "AM"), repository).orReturn { return it }
        if (all.isNotEmpty()) {
            runner.run(RestoreFromCommand(snapshot), repository, pathspecInput(all)).orReturn { return it }
        }
        deleteAll(repository, added).orReturn { return it }
        return Result.Success(before)
    }

    /** Files made since the snapshot go away; links are removed, never followed, and nothing outside the tree is touched. */
    private suspend fun deleteAll(repository: Path, paths: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        val root = repository.toAbsolutePath().normalize()
        try {
            paths.forEach { path ->
                val target = root.resolve(path).normalize()
                if (!target.startsWith(root) || target == root || target.startsWith(root.resolve(".git"))) {
                    throw IOException("$path is outside the working tree")
                }
                Files.deleteIfExists(target)
            }
            Result.Success(Unit)
        } catch (e: IOException) {
            Result.Failure(GitExitException("LHST", listOf("delete"), null, e.message.orEmpty()))
        }
    }

    // --- rewriting the chain ------------------------------------------------------------------------------------

    override suspend fun purge(repository: Path, path: String): Result<Int> {
        val where = locate(repository).orReturn { return it }
        val chain = list(repository, where, Int.MAX_VALUE).orReturn { return it }
        if (chain.isEmpty()) return Result.Success(0)
        var changed = 0
        val trees = HashMap<ObjectId, ObjectId>()
        val rewritten =
            rewrite(repository, where, chain.reversed()) { snapshot ->
                TempIndex.scope(where.gitDir) { index ->
                    runner.run(ReadTreeCommand(snapshot.tree, index.environment), repository).orReturn {
                        return@scope it
                    }
                    runner.run(ForgetPathCommand(index.environment), repository, pathspecInput(listOf(path)))
                        .orReturn { return@scope it }
                    val tree = runner.run(WriteTreeCommand(index.environment), repository).orReturn { return@scope it }
                    if (tree != snapshot.tree) changed++
                    trees[snapshot.id] = tree
                    Result.Success(tree)
                }
            }
        rewritten.orReturn { return it }
        return Result.Success(changed)
    }

    override suspend fun thin(repository: Path): Result<Thinning> {
        val where = locate(repository).orReturn { return it }
        val removed = dropStaleHistories(repository, where).orReturn { return it }
        val chain = list(repository, where, Int.MAX_VALUE).orReturn { return it }
        if (chain.isEmpty()) return Result.Success(Thinning(0, 0, removed))
        var keep = retained(chain, now())
        var usage = runner.run(HistoryDiskUsageCommand(where.ref), repository).orReturn { return it }
        // Over budget: drop the oldest unlabelled ones, as many as the average snapshot size says it takes.
        var rounds = 0
        while (usage > limits.budgetBytes && rounds++ < MAX_BUDGET_ROUNDS) {
            val droppable = keep.filter { it.label == null && it.id != chain.first().id }
            if (droppable.isEmpty()) break
            val perSnapshot = (usage / keep.size).coerceAtLeast(1)
            val count = ((usage - limits.budgetBytes + perSnapshot - 1) / perSnapshot).toInt().coerceIn(
                1,
                droppable.size,
            )
            val gone = droppable.takeLast(count).toSet() // `keep` is newest first, so the oldest are last
            keep = keep.filterNot { it in gone }
            rewrite(repository, where, keep.reversed()) { Result.Success(it.tree) }.orReturn { return it }
            usage = runner.run(HistoryDiskUsageCommand(where.ref), repository).orReturn { return it }
        }
        val dropped = chain.size - keep.size
        if (dropped > 0 && rounds == 0) {
            rewrite(repository, where, keep.reversed()) { Result.Success(it.tree) }.orReturn { return it }
        }
        return Result.Success(Thinning(keep.size, dropped, removed))
    }

    /** Which snapshots the age rules keep, newest first: all of today, one per hour, one per day, labelled ones always. */
    private fun retained(chain: List<Snapshot>, at: Long): List<Snapshot> {
        val hours = HashSet<Long>()
        val days = HashSet<Long>()
        return chain.filterIndexed { index, s ->
            val age = at - s.time
            when {
                index == 0 || s.label != null || age < DAY -> true
                age < WEEK -> hours.add(s.time / HOUR)
                age < MONTH -> days.add(s.time / DAY)
                else -> false
            }
        }
    }

    /** A removed worktree's history is kept 30 days after its last snapshot (Local History, "Edge cases"). */
    private suspend fun dropStaleHistories(repository: Path, where: HistoryLocation): Result<Int> {
        val refs = runner.run(HistoryRefsCommand(), repository).orReturn { return it }
        var removed = 0
        for ((ref, time) in refs) {
            val id = ref.removePrefix("${HistoryLocation.HISTORY}/")
            val gone = id.startsWith("wt-") && !withContext(Dispatchers.IO) {
                Files.isDirectory(where.commonDir.resolve("worktrees").resolve(id.removePrefix("wt-")))
            }
            if (gone && now() - time > MONTH) {
                runner.run(DeleteRefCommand(ref), repository).orReturn { return it }
                removed++
            }
        }
        return Result.Success(removed)
    }

    /**
     * Writes [oldestFirst] as a new chain, one `fast-import` process for all of it, each snapshot with the tree
     * [treeOf] gives and its own time and message, then points the history ref at it only if nobody took a snapshot
     * meanwhile. A snapshot taken during the rewrite makes it fail rather than be lost.
     */
    private suspend fun rewrite(
        repository: Path,
        where: HistoryLocation,
        oldestFirst: List<Snapshot>,
        treeOf: suspend (Snapshot) -> Result<ObjectId>,
    ): Result<Unit> {
        val oldTip = runner.run(ResolveCommand(where.ref), repository).orReturn { return it }
        val script = ByteArrayOutputStream()
        oldestFirst.forEachIndexed { index, snapshot ->
            val tree = treeOf(snapshot).orReturn { return it }
            val message = (snapshot.message + "\n").toByteArray()
            val identity = "Graft <graft@localhost> ${snapshot.time} +0000"
            fun line(s: String) = script.write((s + "\n").toByteArray())
            line("commit ${where.rewriteRef}")
            line("mark :${index + 1}")
            line("author $identity")
            line("committer $identity")
            line("data ${message.size}")
            script.write(message)
            line("")
            if (index > 0) line("from :$index")
            line("deleteall")
            line("M 040000 ${tree.hex} \"\"")
            line("")
        }
        runner.run(DeleteRefCommand(where.rewriteRef), repository).orReturn { return it }
        runner.run(FastImportCommand(), repository, script.toByteArray()).orReturn { return it }
        val tip = runner.run(ResolveCommand(where.rewriteRef), repository).orReturn { return it }
            ?: return Result.Failure(GitExitException("LHST", listOf("fast-import"), null, "no commits were written"))
        val moved = runner.run(UpdateRefCommand(where.ref, tip, oldTip), repository)
        runner.run(DeleteRefCommand(where.rewriteRef), repository)
        return moved
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private suspend fun locate(repository: Path): Result<HistoryLocation> =
        runner.run(LocateHistoryCommand(), repository)

    private fun missing(id: ObjectId) =
        GitExitException("LHST", listOf("rev-parse", id.hex), 1, "fatal: no snapshot ${id.hex}")

    private fun refused(reason: String) =
        GitPatchException("APLY", listOf("apply"), null, "error: $reason", PatchProblem.DOES_NOT_APPLY)

    private companion object {
        const val HOUR = 3_600L
        const val DAY = 24 * HOUR
        const val WEEK = 7 * DAY
        const val MONTH = 30 * DAY
        const val MAX_BUDGET_ROUNDS = 5
    }
}

/** Returns a failure from the caller and gives the value otherwise: `val x = run(...).orReturn { return it }`. */
internal inline fun <T> Result<T>.orReturn(onFailure: (Result.Failure) -> Nothing): T = when (this) {
    is Result.Success -> data
    is Result.Failure -> onFailure(this)
}
