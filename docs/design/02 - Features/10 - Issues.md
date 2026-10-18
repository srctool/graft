---
tags: [graft, feature, issues]
status: seedling
milestone: G6
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 10 - Issues

## List and detail

- Filters: open/closed, assigned to me, created by me, mentioned, labels, milestone, free text (provider search syntax passed through).
- Detail: body (Markdown), comments, labels, assignees, milestone, linked PRs and branches.

## Actions

| Action | Notes |
|---|---|
| Create | title, body (issue templates / forms from `.github/ISSUE_TEMPLATE`), labels, assignees |
| Comment, edit, close/reopen | |
| **Start work** | creates a branch named from a pattern (default `<number>-<slug>`), optionally in a new worktree, links it to the issue (GitHub "development" link) and assigns the user |
| Reference in commit | `#123` autocomplete; `Fixes #123` suggested on the commit of a branch started from the issue |

## Later

Issue-only providers (Jira, Linear) behind the same `IssueApi`, so a GitHub repo can use Jira for issues ([Provider Model](../01%20-%20Foundations/04%20-%20Provider%20Model.md)).
