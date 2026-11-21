---
tags: [graft, feature, markdown, review]
status: seedling
milestone: G2
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 33 - Markdown Preview

Read a `.md` file as rendered text, wherever Graft shows a file: a change in the working tree, a commit, a compare, later a PR. AI agents write many Markdown files (plans, specs, READMEs, changelogs), and reviewing them as raw diff lines is slow. This is part of making Graft the place where you review changes, so you don't open an editor for it.

Reading only. Graft does not edit Markdown files.

## Where it shows

| Place | What the preview shows |
|---|---|
| Changes panel ([Working Copy](04%20-%20Working%20Copy%20and%20Commit.md)) | the file as it is in the working tree; for a staged change, the staged version |
| Commit details and the graph | the file as it is in that commit |
| [Compare Branches](21%20-%20Compare%20Branches.md) | B's version of the file; **Changes** mode below shows A against B |
| [Local History](14%20-%20Local%20History.md) | the snapshot's version of the file |
| [Pull Requests](09%20-%20Pull%20Requests.md) (G6) | the same toggle on the Files tab; the PR description keeps using the same renderer |

## The toggle

Any file ending in `.md`, `.markdown` or `.mdx` gets a three-way switch in the diff viewer's header:

| Mode | Shows |
|---|---|
| **Diff** (default) | the normal line diff, as for any file |
| **Preview** | the new version of the file, rendered |
| **Changes** | the rendered file with what changed marked in place: added blocks tinted, removed blocks shown struck through and collapsed, changed words highlighted |

- The mode is remembered per repository; **Diff** stays the default so a diff is never hidden by surprise.
- A file that is new or deleted has only **Preview** (of the new or the old version) and **Diff**.
- Keyboard: `Mod+Alt+M` cycles the modes while the diff viewer has focus. The toggle is a labelled segmented control, so it reads correctly to a screen reader.
- **Changes** compares the Markdown by block (heading, paragraph, list item, code block, table row), not by source line, then marks words inside a changed block. A reflowed paragraph with the same words shows as unchanged.

## What renders

CommonMark plus GitHub-flavored Markdown: headings, emphasis, lists, task lists (shown as checkboxes, not clickable), tables, fenced code with syntax highlighting (the same `Highlighter` as the diff), blockquotes, footnotes, and front matter (shown as a collapsed key-value block, not as text).

| Case | Decided behavior |
|---|---|
| Raw HTML in the file | not rendered; shown as plain text in a code style. A threat-model rule, [T5](../01%20-%20Foundations/07%20-%20Threat%20Model.md) |
| Mermaid or other diagram fences | shown as a code block (a diagram renderer needs a script engine; not planned) |
| Math (`$…$`) | shown as text |
| Link to another file in the repository | opens that file's preview at the same revision, with a back action |
| Link to a heading in the same file | scrolls to it |
| External link | the real target is shown on hover; click asks once before opening the system browser |
| Image in the repository (relative path) | read from the same revision with `git show`; no network; a missing image shows its alt text and path |
| Image from a URL | not loaded unless **Remote images in Markdown** is on ([Settings Reference](../01%20-%20Foundations/08%20-%20Settings%20Reference.md), off by default) |
| Very large file | over 1 MB, the preview asks before rendering; **Diff** is always available |
| File that is not valid UTF-8 | **Preview** is not offered; the diff viewer's binary or encoding notice shows |
| Light and dark theme | follows the app; code and tables use the same colors as the diff viewer |

Selecting text and copying works. Search (`Mod+F`) works inside the preview.

## Reading code comments on a Markdown file

When the Changes panel or [Agent Worktrees and Review](23%20-%20Agent%20Worktrees%20and%20Review.md) adds comments on a file (G9), a comment made in **Diff** shows beside the rendered block that contains its line in **Preview** and **Changes**. Comments are still made in **Diff**, where lines are exact.

## Rules

- One renderer, `MarkdownView` in `:module:core:ui`, shared by this note, PR and issue bodies and commit messages. It sits behind a Graft interface so the library can change ([Libraries](../04%20-%20Development/07%20-%20Libraries.md)).
- The preview reads file contents through the same repository calls the diff viewer uses; it never runs a git command of its own and never writes.
- No network request unless the user turned on remote images ([Privacy](../03%20-%20Project/07%20-%20Privacy.md)).

## Out of scope

Editing; a Markdown file tree or "docs" view of the whole repository; export to PDF or HTML; live reload while an agent is writing the file (the diff viewer's refresh applies, and the preview follows it).
