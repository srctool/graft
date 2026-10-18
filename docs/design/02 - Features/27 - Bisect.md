---
tags: [graft, feature, bisect]
status: seedling
milestone: G10
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 27 - Bisect

Find the commit that introduced a bug, on the graph.

## Flow

1. **Start:** right-click a commit → **Bisect: mark bad** (default HEAD), then another → **mark good** (e.g. the last release tag). Graft runs `git bisect start <bad> <good>`.
2. **Where:** in the current worktree, or (default) in a **temporary worktree**, so your working copy and branch stay untouched.
3. **Each step:**
   - the banner shows the commit under test and "step 3, about 4 left";
   - the graph shades the commits still in range;
   - buttons: **Good**, **Bad**, **Skip**, **Abort**.
4. **Automatic:** give a command (e.g. `./gradlew test --tests LoginTest`). Graft runs `git bisect run <cmd>` with streamed output. Exit code 0 = good, 125 = skip, anything else = bad.
5. **Result:** the first bad commit with its diff, and actions: open, revert, copy hash, create issue. Then `git bisect reset` (and remove the temporary worktree).

## Extras

- Custom terms (`--term-old=fast --term-new=slow`) for "find when it got slow".
- `git bisect log` saved with the result; **replay** reruns a saved session.
- First-parent only (`--first-parent`) for repos with merge-heavy history.
