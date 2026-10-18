---
tags: [graft, design, keyboard]
status: seedling
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 02 - Keyboard Shortcuts

The default keymap follows JetBrains IDEs where an equivalent exists. **Mod** = `Cmd` on macOS, `Ctrl` on Windows and Linux. Presets: **JetBrains** (default), **GitKraken**, **VS Code**. Every action is remappable in Settings → Keymap, and every action is in Find Action even without a shortcut.

## Global

| Action | Default (JetBrains) | GitKraken preset |
|---|---|---|
| Search Everywhere | `Shift` `Shift` | `Mod+P` |
| Find Action | `Mod+Shift+A` | `Mod+Shift+P` |
| Settings | `Mod+,` | `Mod+,` |
| New tab / Home | `Mod+T` | `Mod+T` |
| Close tab | `Mod+W` | `Mod+W` |
| Next / previous tab | `Mod+Shift+]` / `Mod+Shift+[` | `Ctrl+Tab` / `Ctrl+Shift+Tab` |
| Open repository | `Mod+O` | `Mod+O` |
| Clone | `Mod+Shift+O` | `Mod+N` |
| Switch profile for this repo | `Mod+Alt+P` | same |
| Focus mode (hide panels) | `Mod+Shift+F12` | same |
| Toggle left / right / bottom panel | `Cmd+1` / `Cmd+2` / `Cmd+3` on macOS, `Alt+1` / `Alt+2` / `Alt+3` on Windows and Linux (as in JetBrains) | `Mod+J` (left) |
| Undo / Redo (op log), when no text field has focus | `Mod+Z` / `Mod+Shift+Z` | same |

## Repository

| Action | Default |
|---|---|
| Fetch | `Mod+Alt+F` |
| Pull | `Mod+Alt+U` (see the open question below) |
| Push | `Mod+Shift+K` |
| Commit (focus commit box) | `Mod+K` |
| Commit and push | `Mod+Alt+K` |
| Amend last commit | `Mod+Shift+Alt+K` |
| New branch | `Mod+Alt+B` |
| Branch popup (checkout) | `Mod+Shift+B` |
| Shelve changes | `Mod+Alt+H` |
| Stash | `Mod+Alt+S` |
| Snapshot now | `Mod+Alt+Y` |
| Show local history (file) | `Alt+Shift+H` |
| Refresh | `Mod+R` |

## Graph

| Action | Keys |
|---|---|
| Move selection | `↑` `↓`, `PageUp` `PageDown`, `Home` `End` |
| First parent / child | `Alt+↓` / `Alt+↑` |
| Other parents of a merge | `Alt+→` |
| Previous / next ref | `[` / `]` |
| Open commit details | `Enter` |
| Copy hash | `Mod+C` |
| Find in graph | `Mod+F` |
| Jump to HEAD | `Mod+Alt+Home` |
| Checkout selected | `Mod+Alt+O` |
| Cherry-pick selected | `Mod+Alt+C` |

## Changes and diff

| Action | Keys |
|---|---|
| Stage / unstage file or hunk | `Space` |
| Discard (with confirmation) | `Mod+Alt+Z` |
| Next / previous change | `F7` / `Shift+F7` |
| Next / previous file | `Alt+Shift+→` / `Alt+Shift+←` |
| Move change to other side (`>>`) | `Mod+Shift+→` / `Mod+Shift+←` |
| Toggle side-by-side / unified | `Mod+Alt+D` |
| Move to changelist | `Mod+Shift+M` |

## Merge tool

| Action | Keys |
|---|---|
| Accept left / right change | `Mod+Shift+←` / `Mod+Shift+→` |
| Ignore change | `Mod+Shift+Delete` |
| Apply all non-conflicting | `Mod+Shift+A` inside the merge tool |
| Next / previous conflict | `F7` / `Shift+F7` |
| Mark resolved | `Mod+Enter` |

## Rules

- **No single-letter shortcuts** outside lists, so typing in text fields never triggers actions.
- **Speed search:** typing in a focused list or tree filters it; `Esc` clears.
- **Conflicts:** a shortcut can't be used twice in the same context; the keymap editor shows conflicts.
- **macOS system keys** (`Cmd+Q`, `Cmd+H`, `Cmd+M`, ``Cmd+` ``) are never reassigned.
- Shortcuts shown in tooltips and menus follow the active preset.

> [!NOTE]
> **Open**
> The Pull shortcut collides with `Mod+T` (new tab) in JetBrains' own keymap (where `Mod+T` is "Update Project"). Graft keeps `Mod+T` for tabs and uses `Mod+Alt+U` ("update") for Pull; `Ctrl+Alt+L` was avoided because it locks the screen on some Linux desktops. Revisit after dogfooding.
