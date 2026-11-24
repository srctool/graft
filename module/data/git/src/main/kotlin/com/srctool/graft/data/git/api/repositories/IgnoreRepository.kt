// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.repositories

import com.srctool.graft.data.git.api.models.IgnoreExplanation
import com.srctool.graft.data.git.api.models.IgnorePreview
import com.srctool.graft.data.git.api.models.IgnoreTarget
import com.srctool.graft.data.git.api.models.IgnoreTemplate
import com.srctool.graft.data.git.api.models.IgnoreWrite
import com.srctool.sinew.models.Result
import java.nio.file.Path

/** Ignoring files: where patterns go, what they match, why a file is ignored, and the bundled templates. Codes `GIT-R-IGNR-…`. */
public interface IgnoreRepository {
    /** The file [target] writes to; it may not exist yet. */
    public suspend fun file(repository: Path, target: IgnoreTarget): Result<Path>

    /**
     * What [patterns] would match, without writing them (`ls-files --others --ignored --exclude-from`). [base] is the
     * folder of the `.gitignore` the patterns are for, so a pattern anchored there means what it will mean in the file.
     */
    public suspend fun preview(repository: Path, patterns: List<String>, base: String = ""): Result<IgnorePreview>

    /**
     * Appends [patterns] to [target], leaving out lines the file already has. Existing text is kept byte for byte
     * (a file without a final newline gets one; line endings follow the file's), and the write is atomic.
     */
    public suspend fun add(repository: Path, target: IgnoreTarget, patterns: List<String>): Result<IgnoreWrite>

    /** Why each of [paths] is ignored (`check-ignore -v`, tracked paths included); paths nothing ignores are left out. */
    public suspend fun explain(repository: Path, paths: List<String>): Result<List<IgnoreExplanation>>

    /** Untracks [paths] and keeps the files (`git rm --cached`), so a new ignore pattern takes effect for them. */
    public suspend fun untrack(repository: Path, paths: List<String>): Result<Unit>

    /** The bundled `github/gitignore` templates (CC0). */
    public fun templates(): List<IgnoreTemplate>

    /** The licence text that ships with the templates. */
    public fun templatesLicense(): String
}
