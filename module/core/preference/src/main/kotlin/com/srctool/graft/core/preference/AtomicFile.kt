// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.preference

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Atomic file replacement (Storage Formats 3.1, rule 4): write a temp file next to the target, `fsync` it, then
 * rename it over the target. A crash at any point leaves either the old file or the new one, never half of either.
 */
public object AtomicFile {
    /**
     * Replaces [path] with [bytes], creating its folder if needed. Blocking: call it on an I/O dispatcher.
     * [beforeMove] runs after the temp file is synced and before the rename; tests use it to simulate a crash.
     */
    public fun write(path: Path, bytes: ByteArray, beforeMove: () -> Unit = {}) {
        val dir = path.toAbsolutePath().parent
        Files.createDirectories(dir)
        // A dot-prefixed temp name in the same folder: same file system, so the rename can be atomic.
        val temp = Files.createTempFile(dir, ".${path.fileName}.", ".tmp")
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            beforeMove()
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
            syncFolder(dir)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /** Makes the rename itself durable. Windows can't open a folder for syncing, and doesn't need to. */
    private fun syncFolder(dir: Path) {
        try {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
        } catch (_: UnsupportedOperationException) {
        }
    }
}
