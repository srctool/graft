---
tags: [graft, feature, graph]
status: seedling
milestone: G1
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 01 - Commit Graph

The main view: one row per commit, lanes on the left, then refs, message, author, date.

## What appears on the graph

| Item | Shown as |
|---|---|
| Local branch | filled label; checked-out branch bold with a HEAD marker |
| Remote branch | outlined label with the remote's icon; merged into the local label when they point at the same commit (`main ⇄ origin/main`) |
| Ahead/behind | `↑2 ↓3` on a tracking branch label |
| Tag | tag label (annotated tags show the message on hover) |
| Stash | a hollow node hanging off its base commit |
| Shelf | a dashed node off its base commit (toggleable) |
| Worktree HEAD | a folder badge with the worktree name |
| Open PR | a PR badge on the head branch with state and check status |
| Uncommitted changes | a "WIP" row at the top on HEAD's lane |
| Merge / rebase in progress | banner above the graph with Continue / Skip / Abort |

## Layout algorithm

Input: commits in `--topo-order` (children before parents).

1. Keep an array `lanes[]` of commit hashes each lane is waiting for.
2. For each commit `c`:
   - its lane is the first lane waiting for `c`; if none, the first free lane (a new branch tip);
   - other lanes waiting for `c` end here (merge lines into `c`);
   - the first parent takes `c`'s lane; each other parent takes a lane already waiting for it, or a new free lane.
3. Emit a row: `c`'s lane, and the line segments entering and leaving each lane.

The algorithm is incremental, so it runs as `git log` streams. Lane colors are stable per branch, not per lane index. Rows are fixed height and drawn with one Compose `Canvas` per row inside a virtualized `LazyColumn`.

## Interaction

- Click: commit details (message, author, committer, signature status, parents, changed files, diff).
- Multi-select two commits: diff between them. Select a range: squash, or create a patch.
- Right-click: checkout, branch here, tag here, cherry-pick, revert, reset (soft/mixed/hard), rebase onto, interactive rebase from here, copy hash, open on GitHub.
- Drag a branch label onto another: merge / rebase menu.
- **Left panel:** branches, remotes, tags, stashes, shelves, worktrees, submodules, PRs, issues. Each item has hide, solo and pin.
- **Search:** message, hash, author, file path (`git log -- <path>`), content (`-S`/`-G`).
- **Filter:** show all refs / current branch only / selected branches; hide remotes; first-parent only; **by author type**: people, AI co-authored (`Co-Authored-By` trailers from Claude, Cursor, Copilot), bots (`dependabot[bot]`, `github-actions[bot]`).
- **CI status** dot on branch labels and commits ([CI and Actions](24%20-%20CI%20and%20Actions.md)); keyboard and screen-reader rules in [Accessibility](../01%20-%20Foundations/05%20-%20Accessibility.md).

## Performance targets

| Repository | Target |
|---|---|
| 10k commits | full graph laid out < 300 ms |
| 500k commits | first screen < 1 s, scrolling at 60 fps, memory < 400 MB |

Commit rows store only hash, parents, author index, time and subject offset; full messages load on demand through `cat-file --batch`.

## Edge cases

| Case | Decided behavior |
|---|---|
| Orphan branches / several roots | separate lanes, each starts its own column |
| Octopus merge | one lane per parent, drawn fanning in |
| Commit dates out of order | topo order wins; date column may decrease |
| Grafts / replace refs | shown as Git sees them, with a "replaced" marker |
| Detached HEAD | HEAD marker on the commit, warning when committing |
