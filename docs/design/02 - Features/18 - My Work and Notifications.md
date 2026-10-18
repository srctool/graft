---
tags: [graft, feature, dashboard, notifications]
status: seedling
milestone: G6
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 18 - My Work and Notifications

The screen to open every morning: everything that needs you, across **all** repositories of your accounts, not only the ones cloned locally (GitKraken calls this Launchpad).

## Dashboard sections

| Section | Source (GitHub search) |
|---|---|
| **Review requested** | `is:open is:pr review-requested:@me` |
| **My pull requests** | `is:open is:pr author:@me`, with CI, review state, mergeable/conflicts, "changes requested" first |
| **Assigned issues** | `is:open assignee:@me` |
| **Mentions** | `is:open mentions:@me` |
| **Agent PRs** (optional) | PRs from branches started in [agent worktrees](23%20-%20Agent%20Worktrees%20and%20Review.md) |

- Grouped by repository; filterable by profile, workspace, org, label.
- **Profiles:** each profile's dashboard shows its own accounts. The "All profiles" view mixes them, with a profile chip on each item.

## Item actions

| Action | Effect |
|---|---|
| Open in Graft | opens the repository (offers to clone if missing). For a PR: check it out, or open it in a new worktree ([Pull Requests](09%20-%20Pull%20Requests.md)) |
| Start work (issue) | branch or worktree from the issue ([Issues](10%20-%20Issues.md)) |
| Open in browser | |
| Pin / Snooze (1 h, tomorrow, next week) / Mark done | local state, per profile |

## Notifications

- **Inbox:** the GitHub notifications API (`/notifications`), with `Last-Modified` and the `X-Poll-Interval` it returns. Marking read in Graft marks it read on GitHub.
- **Desktop notifications** for chosen reasons:

  | Reason | Default |
  |---|---|
  | Review requested | on |
  | CI failed on my PR | on |
  | My PR approved / changes requested | on |
  | My PR merged or closed | on |
  | Mentioned | on |
  | New comment on my PR | off |

- **Click** opens the item in Graft.
- **Quiet:** do-not-disturb hours, follows the OS focus mode, and mutes per repository or org.

## Edge cases

| Case | Decided behavior |
|---|---|
| Search API rate limit (30/min) | one combined query per section, cached; refresh every 2 min while visible |
| Org requires SSO authorization for the token | item shows "authorize SSO for acme" with a link |
| Same PR in two profiles' views | shown once per profile; "All profiles" deduplicates |
