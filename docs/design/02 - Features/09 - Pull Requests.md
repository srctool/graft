---
tags: [graft, feature, pull-requests]
status: seedling
milestone: G6
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 09 - Pull Requests

Neutral name in code: `ChangeRequest` ([Provider Model](../01%20-%20Foundations/04%20-%20Provider%20Model.md)). GitHub first.

## List

- Filters: open / closed / merged; authored by me, review requested, assigned, mentions; base branch; labels; draft.
- Each row: number, title, author, branch → base, checks status, review state, comments count, updated.
- Badges on the graph for PRs whose head branch exists locally or remotely.

## Detail

| Tab | Content |
|---|---|
| Overview | description (Markdown), labels, assignees, reviewers, milestone, linked issues, mergeability, checks |
| Commits | the PR's commits, clickable into the graph |
| Files | diff with inline review threads; mark file as viewed |
| Conversation | timeline of comments, reviews, events |
| Checks | check runs and statuses, with links to logs |

## Actions

| Action | GitHub API |
|---|---|
| Create | from current branch: title/body (PR template from `.github/`), base, draft, reviewers, labels; pushes first if needed |
| Checkout locally | `git fetch origin pull/<n>/head:pr/<n>` or the fork's branch with a tracking remote; or **into a new worktree** |
| Review | pending review: line/range comments, suggestions (```suggestion blocks), Approve / Request changes / Comment |
| Reply / resolve threads | GraphQL `addPullRequestReviewThreadReply`, `resolveReviewThread` |
| Update branch | merge or rebase base into head (server-side) |
| Merge | merge / squash / rebase, as allowed by repo settings; delete branch after; auto-merge and merge queue if enabled |
| Edit | title, body, draft ↔ ready, reviewers, labels, close / reopen |

## Edge cases

| Case | Decided behavior |
|---|---|
| PR from a fork | adds the fork as a remote on checkout (asked), or fetches `pull/<n>/head` without a remote |
| Head branch force-pushed during review | outdated threads marked; diff refreshes |
| No permission to merge | merge button hidden; reason shown |
| Repo has both `origin` (fork) and `upstream` | PRs listed against the upstream by default |
