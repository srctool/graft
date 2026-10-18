---
tags: [graft, feature, ignore]
status: seedling
milestone: G2
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 20 - Ignore and Clean

## Ignore from the changes panel

Right-click an untracked file or folder → **Ignore**:

| Choice | Pattern |
|---|---|
| This file | `/path/to/file.log` |
| All with this extension | `*.log` |
| This folder | `/build/` |
| Custom pattern | free text, with a live preview of what it matches |

Then choose **where** it goes:

| Target | Shared? |
|---|---|
| Repository `.gitignore` (root or nearest) | yes, committed |
| `.git/info/exclude` | no, this clone only |
| Global excludes (`core.excludesFile`) | no, all repos on this machine; created if missing |

If the file is **already tracked**, ignoring it does nothing in Git. Graft explains this and offers `git rm --cached <path>`, which untracks the file without deleting it.

## `.gitignore` editor

- Syntax highlighting, and the files each line matches, live (`git ls-files --others --ignored --exclude-from=…`).
- Templates from the `github/gitignore` collection (bundled; CC0), merged without duplicating lines.
- **Why is this ignored?** On any file: `git check-ignore -v` shows the file, line and pattern responsible.

## Clean

`git clean` removes untracked files, so Graft always previews first:
1. `git clean -n -d` (and `-x` to include ignored files, or `-X` for only ignored files) lists what would be removed.
2. The user ticks the files to delete. Sizes are shown, largest first (handy for `node_modules`, `build/`).
3. A [Local History](14%20-%20Local%20History.md) snapshot is taken (untracked, non-ignored files only; the dialog warns that ignored files can't be restored).
4. Graft runs `git clean -f -- <selected paths>`.

## Edge cases

| Case | Decided behavior |
|---|---|
| Negation patterns (`!keep.log`) | preview shows them correctly; the editor warns when a negation can't work (parent folder ignored) |
| Nested repositories in untracked folders | never cleaned unless explicitly ticked (`-ff`) |
| Submodule folders | excluded from clean |
