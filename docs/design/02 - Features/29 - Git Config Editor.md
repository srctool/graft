---
tags: [graft, feature, config]
status: seedling
milestone: G10
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 29 - Git Config Editor

See and edit Git configuration, with **where each value comes from**.

## Effective config view

For the current repository: `git config --list --show-origin --show-scope -z`. Grouped by section (`user`, `core`, `pull`, `remote "origin"`…).

Each key shows:
- its value and scope (system, global, Graft profile file, local, worktree, command line);
- **overrides**, e.g. "set in global, overridden by local";
- docs for known keys (type, default, description), bundled from Git's documentation.

## Editing

| Scope | Editable |
|---|---|
| System | read-only (needs admin) |
| Global `~/.gitconfig` | yes, except Graft's managed block |
| Profile file | via [Profiles](11%20-%20Profiles.md) only |
| Local `.git/config` | yes |
| Worktree `config.worktree` | yes; offers to enable `extensions.worktreeConfig` first |

Editing includes add, change, remove (`git config --unset`), and multi-value keys (`--add`, `--unset-all`). Types are validated (bool, int, path, color).

## Also

- **Aliases** editor, with a "try it" run.
- **Includes** viewer: which `include` / `includeIf` files apply here and why.
- **Attributes** viewer: `git check-attr -a <file>` for any file (line endings, LFS, diff drivers).
- **Hooks** viewer: which hooks exist (`core.hooksPath` or `.git/hooks`), and from which hook manager.
