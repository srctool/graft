// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.configuration.instance

import com.srctool.graft.core.configuration.AppFolders
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Where the running app listens and keeps its token (Platform Integration 3.5, Threat Model T13): a folder only the
 * user can enter, holding `app.sock` and `token`.
 */
public class InstanceFolder(public val dir: Path) {
    public val socket: Path get() = dir.resolve(SOCKET)
    public val token: Path get() = dir.resolve(TOKEN)

    /** Creates the folder for the user alone (mode 0700; on Windows it sits in the user's own profile). */
    public fun ensure(): Path {
        if (Files.notExists(dir)) {
            val parent = dir.toAbsolutePath().parent
            if (parent != null) Files.createDirectories(parent)
            if (supportsPosix(dir)) {
                Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR))
            } else {
                Files.createDirectory(dir)
            }
        }
        if (supportsPosix(dir)) Files.setPosixFilePermissions(dir, OWNER_ONLY_DIR)
        return dir
    }

    public companion object {
        private const val SOCKET = "app.sock"
        private const val TOKEN = "token"

        /** Longest socket path in bytes that every supported OS accepts (macOS allows 104 with its terminator). */
        internal const val MAX_SOCKET_BYTES = 100

        internal val OWNER_ONLY_DIR = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        internal val OWNER_ONLY_FILE = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

        /**
         * `<cache>/run`, unless its socket path is too long for the OS (a long user name on macOS): then a `graft-<user>`
         * folder in [tempDir], which [ensure] also creates for the user alone.
         */
        public fun of(folders: AppFolders, tempDir: Path, user: String): InstanceFolder {
            val preferred = InstanceFolder(folders.cache.resolve("run"))
            if (preferred.socket.toString().toByteArray(StandardCharsets.UTF_8).size <=
                MAX_SOCKET_BYTES
            ) {
                return preferred
            }
            return InstanceFolder(
                tempDir.resolve(
                    "graft-" + user.filter {
                        it.isLetterOrDigit() || it == '-' || it == '_'
                    },
                ),
            )
        }

        internal fun supportsPosix(path: Path): Boolean = Files.getFileStore(
            path.toAbsolutePath().let {
                if (Files.exists(it)) it else it.parent
            },
        ).supportsFileAttributeView("posix")
    }
}
