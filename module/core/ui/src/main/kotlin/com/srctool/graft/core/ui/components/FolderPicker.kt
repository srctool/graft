// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JFileChooser

/** Asks the user for a folder with the OS's own dialog; null when they cancel. Screens call it from a click. */
public fun interface FolderPicker {
    public fun pick(title: String, start: Path?): Path?
}

/** The picker screens use; tests provide their own so no native dialog opens. */
public val LocalFolderPicker: androidx.compose.runtime.ProvidableCompositionLocal<FolderPicker> =
    staticCompositionLocalOf { SystemFolderPicker }

/**
 * macOS' native open panel, set to choose folders (the AWT property is what turns that on), and Swing's chooser
 * elsewhere, which Compose Desktop can show on the UI thread.
 */
internal object SystemFolderPicker : FolderPicker {
    override fun pick(title: String, start: Path?): Path? =
        if (System.getProperty("os.name").startsWith("Mac")) macPick(title, start) else swingPick(title, start)

    private fun macPick(title: String, start: Path?): Path? {
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
            start?.let { dialog.directory = it.toString() }
            dialog.isVisible = true
            val directory = dialog.directory ?: return null
            val file = dialog.file ?: return null
            return Paths.get(directory, file)
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    }

    private fun swingPick(title: String, start: Path?): Path? {
        val chooser = JFileChooser(start?.toFile()).apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
    }
}
