---
tags: [graft, feature, ai, worktrees]
status: seedling
milestone: G9
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 23 - Agent Worktrees and Review

Run AI agents in parallel without them touching each other, then review what they did hunk by hunk before it's committed or merged. Builds on [Worktrees](07%20-%20Worktrees.md), [Terminal and AI Agents](13%20-%20Terminal%20and%20AI%20Agents.md) and [Local History](14%20-%20Local%20History.md).

## New agent task

One dialog:
1. **Base:** a branch, or "from issue #123" (prefills branch name and prompt).
2. **Branch name:** default `agent/<slug>`.
3. **Worktree:** created with `git worktree add -b`, inheriting the repo's profile.
4. **Agent launcher** and optional **prompt**.
5. Graft then starts the agent in that folder: in the user's terminal app (Terminal, iTerm2, Windows Terminal, GNOME Terminal), or in the built-in terminal once that exists.

**Agent launchers** are configurable command templates:

| Preset | Command |
|---|---|
| Claude Code | `claude "{prompt}"` in `{path}` |
| Cursor | `cursor {path}` |
| Custom | any command with `{path}`, `{branch}`, `{prompt}`, `{issue}` |

## Agents panel

One row per agent worktree:

| Column | Source |
|---|---|
| Task / branch | name, base |
| Activity | **active** if files changed in the last minute (watcher), else idle for N min |
| Changes | files changed, +/− lines, commits made since base |
| Overlap | ⚠ when two agent worktrees change the same files |
| Process | running / exited (only for agents Graft launched, tracked by PID) |

Row actions: **Review**, open terminal there, open in editor, stop (Graft-launched processes only), **Create PR**, merge into base, discard worktree (snapshot first).

## Review mode

A live diff of the worktree against its base: agent commits plus uncommitted changes, updating as the agent works.

| Per hunk | Effect |
|---|---|
| **Accept** | stages it (or moves it to an "Accepted" changelist) |
| **Reject** | reverts that hunk in the file. If the agent is still active, Graft warns, because it may rewrite the hunk |
| **Comment** | attach a note to file:line |

- **Send feedback:** collects the comments as `path:line: comment` lines and copies them to the clipboard, ready to paste to the agent. Agents can also fetch it themselves with `graft review feedback` or the [MCP server](32%20-%20MCP%20Server.md) tool `graft_review_feedback`.
- **Commit accepted:** commits only the accepted hunks. The agent's `Co-Authored-By` trailer is kept, or added if missing.
- **Finish:** create a PR from the branch, or merge into the base locally, then remove the worktree.

## Edge cases

| Case | Decided behavior |
|---|---|
| Agent commits while you review | the review refreshes; already accepted or rejected hunks keep their state if unchanged |
| Agent runs `reset --hard` or rebases | [external rewrite banner](16%20-%20Reflog%20and%20Recovery.md) plus Local History |
| Agent launched outside Graft in a worktree | still listed (by activity); no process control |
| Base branch moves on | "base moved" badge; rebase onto the new base from the row menu |
