---
tags: [graft, foundations, provider]
status: seedling
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 04 - Provider Model

Hosting services (GitHub first) plug in through one contract in `core-provider` ([ADR-4](../03%20-%20Project/02%20-%20Decisions.md)). The core and the features speak only in neutral terms.

## Neutral vocabulary

| Graft term | GitHub | GitLab | Bitbucket | Azure DevOps |
|---|---|---|---|---|
| `ChangeRequest` | Pull request | Merge request | Pull request | Pull request |
| `Review` | Review | Approval / review | Approval | Vote |
| `Check` | Check run / status | Pipeline job | Build status | Build / policy |
| `Issue` | Issue | Issue | Issue (or Jira) | Work item |
| `HostedRepo` | owner/repo | group/subgroup/project | workspace/repo | org/project/repo |

## Contract

```kotlin
interface HostingProvider {
    val id: ProviderId                     // "github"
    val capabilities: Set<Capability>      // DRAFTS, SUGGESTED_CHANGES, MERGE_QUEUE, ...
    fun matches(remote: RemoteUrl): HostedRepoRef?
    suspend fun authenticate(host: Host): Result<Account>
    fun changeRequests(): ChangeRequestApi
    fun issues(): IssueApi
    fun checks(): ChecksApi
    fun users(): UserApi
}
```

- **Capabilities, not `if (github)`.** The UI hides what a provider can't do (for example "suggested change" blocks).
- **Remote matching.** Each remote URL (`git@github.com:o/r.git`, `https://ghe.corp/o/r`) is matched to a provider and host. A repository can have several (`origin` on a fork, `upstream` on the parent).
- **Hosts are configurable** so GitHub Enterprise Server and self-hosted GitLab work the same way.

## Authentication (GitHub)

| Method | Notes |
|---|---|
| **OAuth device flow** (default) | no client secret needed, so it's safe in open-source code; the user enters a code on github.com |
| Personal access token | fallback, and for GHES instances without the OAuth app |
| `gh` CLI token | if `gh auth token` works, offer to reuse it |

Tokens live only in the OS keychain, per profile, host and account. Accounts belong to a [profile](../02%20-%20Features/11%20-%20Profiles.md): each profile has one default account per host, so a Personal and a Work account on github.com don't mix. A repository uses its profile's account unless the user picks another.

**Git transport is separate.** Pushing and fetching use the user's existing git credential helper or SSH key. If HTTPS has no credentials, Graft's askpass helper can answer with the provider token for that host (opt-in).

## Data and rate limits

- GraphQL for lists and details (one round trip per screen); REST for things GraphQL lacks.
- Responses cached in SQLite with ETags; conditional requests don't cost REST rate limit.
- Polling only while a pane is visible: PR list every 60 s, an open PR every 20 s, backing off when the window is unfocused.
- Rate limit shown in the status bar when below 10 %.

## Adding a provider later

1. New module `provider-<name>` implementing `HostingProvider`.
2. Map its objects to the neutral models; declare capabilities.
3. Register it in `app-desktop`.
4. Pass the shared provider contract test suite (`core-provider-testkit`) against recorded fixtures.

Planned order after GitHub: GitLab, Gitea/Forgejo (Codeberg), Bitbucket Cloud, Azure DevOps. Jira and Linear as **issue-only** providers.
