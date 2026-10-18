---
tags: [graft, feature, stacks]
status: seedling
milestone: G9
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 25 - Stacked Branches

A **stack** is a chain of branches, each built on the previous one: `main ← api ← ui ← tests`, each with its own small PR. This is the way Graphite and GitButler work. Agents tend to produce many small dependent changes, so stacks grow in value ([ADR-16](../03%20-%20Project/02%20-%20Decisions.md)).

## Storage

Each branch's parent is stored in plain git config, so other tools and the CLI can read it:

```ini
[branch "ui"]
    graftParent = api
    graftParentTip = 3f2a9c1   # parent's tip when ui was last restacked
```

## Operations

| Operation | How |
|---|---|
| **New branch on top** | creates a branch from the current one and records the parent |
| **Restack** | rebases every branch in the stack onto its updated parent in one go: `git rebase --update-refs` on the top branch (Git ≥ 2.38) |
| **Sync stack** | fetch; rebase the bottom branch onto the updated `main`; restack the rest |
| **Push stack** | `--force-with-lease` for each branch that changed |
| **Submit stack** | create or update one PR per branch, with base = its parent branch; each PR description gets a stack list (`#12 → #13 → #14`) kept up to date |
| **Bottom PR merged** | retarget the next PR's base to `main`, restack, and delete the merged branch locally |
| Reorder / move a branch | change its parent, then restack |
| Insert a branch in the middle | new branch on the parent, re-parent the child |

## UI

- **Stacks panel:** each stack as a vertical list with PR state and CI per branch, and a "needs restack" badge when a parent moved.
- **Graph:** stacked branches highlighted along one lane.

## Edge cases

| Case | Decided behavior |
|---|---|
| Bottom PR was **squash**-merged | the old commits aren't in `main`; restack uses `git rebase --onto main <graftParentTip>` to drop them |
| Conflict mid-restack | normal conflict flow; Continue resumes the rest of the stack |
| Someone else pushed to a branch in the stack | sync pulls it first; if diverged, asks |
| Branch used by two stacks | not allowed; one parent per branch |
| Stack branch checked out in another worktree | `--update-refs` skips it; Graft warns and offers to restack from there |
