---
tags: [graft, feature, working-copy]
status: seedling
milestone: G2
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 04 - Working Copy and Commit

Not listed in the original wish list, but every other feature needs it: selecting changes is the basis for commits, stashes and shelves.

## Changes panel

Two lists, **Unstaged** and **Staged**, from `git status --porcelain=v2`. Tree or flat view. Each file shows its status (M, A, D, R, C, U, ?, ignored on request).

## Selection model

One `ChangeSelection` type is used by **commit, stash, shelf and discard**:

```kotlin
data class ChangeSelection(val files: List<FileSelection>)
data class FileSelection(val path: Path, val scope: Scope) // WholeFile | Hunks(ids) | Lines(ranges)
```

Hunk and line staging builds a patch and applies it with `git apply --cached` (unstage: `-R`). Discard applies the reverse patch to the working tree, after confirmation, and keeps a backup shelf for undo.

## Diff viewer

- Unified and side-by-side; word-level highlights; syntax highlighting.
- Ignore whitespace, show/hide context, expand context.
- Image diff (side by side, swipe, onion skin) and binary info.
- Submodule pointer changes show the commits between the two pointers.
- File history and blame (`git log --follow`, `git blame --porcelain`).

## Commit

- Summary + description fields with a 50/72 guide; commit template (`commit.template`) and conventional-commit prefixes optional.
- Amend, sign-off, GPG/SSH signing (from git config), co-authors picker (from provider collaborators), `--no-verify` explicit.
- Issue references autocomplete (`#123`) from the issue provider.

## Edge cases

| Case | Decided behavior |
|---|---|
| File with mixed CRLF/LF | staging lines keeps the file's endings |
| Large or binary files | no inline diff above 2 MB unless asked |
| LFS pointers | shown as LFS files; diff the real content if `git lfs` is installed |
| Executable bit change only | shown as a mode change |
