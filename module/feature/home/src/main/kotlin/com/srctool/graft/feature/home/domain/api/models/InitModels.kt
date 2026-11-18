// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.home.domain.api.models

import com.srctool.graft.data.git.api.models.RepositoryLocation
import java.nio.file.Path

/** What **Init** (S3) creates: the repository, and the files it starts with. */
internal data class InitOptions(
    val folder: Path,
    val defaultBranch: String = "main",
    /** A bundled .gitignore template's name, or null for none. */
    val gitignore: String? = null,
    /** A bundled license's SPDX id, or null for none. */
    val license: String? = null,
    /** Who the license's copyright line names, for the licenses that have one. */
    val holder: String = "",
    val readme: Boolean = false,
    val firstCommit: Boolean = false,
    /** The year the license's copyright line shows. */
    val year: Int = java.time.Year.now().value,
) {
    /** The files Init writes, in the order it writes them. */
    val files: List<InitFile>
        get() = buildList {
            if (gitignore != null) add(InitFile.GITIGNORE)
            if (license != null) add(InitFile.LICENSE)
            if (readme) add(InitFile.README)
        }
}

/** A file Init writes, and what it's called in the working tree. */
internal enum class InitFile(val fileName: String) {
    GITIGNORE(".gitignore"),
    LICENSE("LICENSE"),
    README("README.md"),
}

/** What a step of Init was: the pieces after `git init`, which can fail while the repository already exists. */
internal sealed interface InitStep {
    data class Writing(val file: InitFile) : InitStep

    data object Committing : InitStep
}

internal sealed interface InitOutcome {
    /** The repository exists with everything asked for. */
    data class Created(val location: RepositoryLocation) : InitOutcome

    /** `git init` worked, then [step] failed: the repository stays, and the dialog says which step. */
    data class Partial(val location: RepositoryLocation, val step: InitStep, val error: Throwable) : InitOutcome

    /** `git init` itself failed (a folder that can't be made, a branch name git refuses). */
    data class Failed(val error: Throwable) : InitOutcome
}

/** A license Init can write: its SPDX id and its name. */
internal data class LicenseInfo(val id: String, val name: String, val needsHolder: Boolean)
