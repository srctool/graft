// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.app.implementation.repositories

import com.srctool.graft.data.app.api.repositories.FolderScanRepository
import com.srctool.graft.data.app.implementation.source.local.RepositoryFolders
import com.srctool.sinew.exception.ExceptionHandler
import com.srctool.sinew.exception.StorageReadException
import com.srctool.sinew.exception.processCall
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.coroutineContext

internal class FileFolderScanRepository : FolderScanRepository {
    override suspend fun scan(folder: Path, maxDepth: Int): Result<List<Path>> {
        require(maxDepth >= 0) { "maxDepth must be ≥ 0" }
        return processCall(MODULE, FUNCTION, ExceptionLayer.Repository, handlers) {
            val context = coroutineContext
            withContext(Dispatchers.IO) {
                val root = folder.toRealPath()
                val found = ArrayList<Path>()
                // Depth counts folders under the root, plus one so a repository's own markers are visible.
                Files.walkFileTree(
                    root,
                    emptySet(),
                    maxDepth + 1,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                            context.ensureActive()
                            if (dir != root &&
                                dir.fileName.toString().startsWith('.')
                            ) {
                                return FileVisitResult.SKIP_SUBTREE
                            }
                            if (RepositoryFolders.looksLikeRepository(dir)) {
                                found.add(dir)
                                return FileVisitResult.SKIP_SUBTREE
                            }
                            return FileVisitResult.CONTINUE
                        }

                        // An unreadable folder is skipped, not fatal: a scan of ~/Projects shouldn't stop at one.
                        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                            if (file == root) throw exc else FileVisitResult.CONTINUE
                    },
                )
                found.sortedWith(folderOrder)
            }
        }
    }

    private companion object {
        const val MODULE = "DATA"
        const val FUNCTION = "SCAN"

        // Folder by folder, by name: `Path.compareTo` compares whole paths with each OS's own rules (on Windows,
        // `app` came before `a\b`), and the list should read like the folder tree everywhere.
        val folderOrder =
            Comparator<Path> { a, b ->
                val common = minOf(a.nameCount, b.nameCount)
                (0 until common).firstNotNullOfOrNull { i ->
                    a.getName(i).toString().compareTo(b.getName(i).toString()).takeIf { it != 0 }
                } ?: a.nameCount.compareTo(b.nameCount)
            }
        val handlers =
            listOf(
                ExceptionHandler({ it is IOException }) {
                    StorageReadException(MODULE, ExceptionLayer.Repository, FUNCTION, it)
                },
            )
    }
}
