---
tags: [graft, design, ui]
status: seedling
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 01 - Screen Inventory

Every screen and view, where it opens, and its states. Layout rules and the main window are in [UI Design](../01%20-%20Foundations/06%20-%20UI%20Design.md); words in [UX Writing](03%20-%20UX%20Writing.md); keys in [Keyboard Shortcuts](02%20-%20Keyboard%20Shortcuts.md).

**Where:** *center* replaces the graph with a breadcrumb back; *right* is the right panel; *left* is a left-panel section; *tab* is its own repo tab; *dialog* is modal; *popup* is a flyout; *bottom* is the bottom tool window.

## States every view handles

| State | Shows |
|---|---|
| Loading | skeleton rows (no spinner for under 300 ms) |
| Empty | one sentence saying why it's empty, plus the action that fills it ("No shelves yet. Shelve changes from the changes panel.") |
| Error | the message, its error code, **Retry**, and **Show output** for git failures |
| Offline | provider views show cached data with "Offline: showing data from 10:42" |
| Not signed in | provider views show "Sign in to GitHub for <profile>" |
| Busy (operation running) | the affected controls disabled, with progress in the status bar |

## Screens

| # | Screen | Where | Contents | Milestone |
|---|---|---|---|---|
| S1 | **Home** | tab | recent and pinned repos, workspaces, search, clone/open/init buttons | G1 |
| S2 | **Clone** | dialog | URL or account repo browser, folder, profile, options (depth, submodules, partial) | G1 |
| S3 | **Init** | dialog | folder, default branch, `.gitignore` template, license, README, create on GitHub | G1 |
| S4 | **Main window** | tab | toolbar, left panel, graph, right panel, bottom, status bar | G1 |
| S5 | **Commit details** | right | message, author/committer, signature, parents, files (tree/list) | G1 |
| S6 | **Diff viewer** | center | file list, side-by-side / unified, word highlights, image diff, `>>` arrows | G1 |
| S7 | **Compare** | center | A/B pickers, commits both ways, combined diff, merge preview | G1 |
| S8 | **Reflog timeline** | bottom | entries with source, actions | G1 |
| S9 | **Changes panel** | right | Staged/Unstaged or changelists, commit box, guards result | G2 |
| S10 | **Commit guard result** | dialog | findings (masked), fix actions, allow once | G2 |
| S11 | **Local history** | center | tree timeline, file timeline, deleted files, restore | G2 |
| S12 | **Ignore / clean** | dialog | ignore pattern + target; clean preview list | G2 |
| S13 | **Branch popup** | popup | recent / local / remote, speed search, actions | G3 |
| S14 | **Merge / rebase preview** | dialog | incoming commits, files, predicted conflicts | G3 |
| S15 | **Interactive rebase** | center | todo list with drag reorder and pick/squash/fixup/drop | G3 |
| S16 | **Conflict list + merge tool** | center | conflicted files; three-pane merge | G3 |
| S17 | **Push / pull** | popup | remote, branch, mode, force-with-lease confirmation | G3 |
| S18 | **Profiles** | settings page | list, identity, rules, accounts, SSH mode, `~/.gitconfig` diff preview | G3 |
| S19 | **SSH wizard** | dialog | find/generate key, agent, GitHub upload, host key verify, test | G3 |
| S20 | **Doctor** | dialog | checks with fix buttons | G3 |
| S21 | **Shelves** | left + center | list; shelf detail with per-file/hunk selection | G4 |
| S22 | **Stashes** | left + center | list; stash diff | G4 |
| S23 | **Worktrees** | left + dialog | list with status; create/move/remove dialogs | G5 |
| S24 | **Submodules** | left | tree with states and actions | G5 |
| S25 | **My Work** | tab (Home) | review requests, my PRs, issues, mentions | G6 |
| S26 | **Pull request** | center | overview, commits, files with threads, conversation, checks | G6 |
| S27 | **Create PR** | dialog | title, body (template), base, reviewers, draft | G6 |
| S28 | **Issue** | center | body, comments, start work | G6 |
| S29 | **Notifications** | popup | inbox with mark read | G6 |
| S30 | **Agents panel** | left + center | agent worktrees, activity, overlap | G9 |
| S31 | **Review mode** | center | live diff, accept/reject/comment, send feedback | G9 |
| S32 | **Actions** | center + bottom | runs, jobs, logs, annotations, dispatch form | G9 |
| S33 | **Stacks** | left | stacks with PR and CI per branch, restack | G9 |
| S34 | **Bisect** | banner + graph | step banner, range shading, result | G10 |
| S35 | **Config editor** | settings page | effective config with origins | G10 |
| S36 | **History rewriting** | dialog | operation, preview, backup, warnings | G10 |
| S37 | **Settings** | dialog | searchable tree, scopes ([Settings Reference](../01%20-%20Foundations/08%20-%20Settings%20Reference.md)) | G1+ |
| S38 | **Onboarding** | dialog (first run) | ten steps ([Onboarding](../02%20-%20Features/22%20-%20Onboarding.md)) | G1, G3 |
| S39 | **Search Everywhere** | popup | repos, branches, commits, files, PRs, actions | G1 |
| S40 | **About** | dialog | version, license (GPL "no warranty"), third-party licenses | G1 |

## Before building each screen

1. A wireframe in the milestone's plan (ASCII is fine) with every state from the table above.
2. Its keyboard path (no mouse needed) and screen-reader labels ([Accessibility](../01%20-%20Foundations/05%20-%20Accessibility.md)).
3. Screenshot tests for light, dark and each state ([Testing Strategy](../04%20-%20Development/03%20-%20Testing%20Strategy.md)).
