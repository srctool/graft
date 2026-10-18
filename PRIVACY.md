# Privacy

**Graft collects nothing about you.** No analytics, no telemetry, no usage statistics, no identifiers. This page lists every network request Graft makes. When a feature adds one, this page changes in the same pull request.

## What Graft sends, and when

| Request | To | Sends | When | Off switch |
|---|---|---|---|---|
| Git fetch, pull, push, clone | your remotes | what Git sends (Git does this, not Graft) | when you, or background fetch, run it | background fetch setting |
| Provider API (GitHub) | the provider host | your token and the requests for the data shown | while provider views are open, polling at the intervals in Settings | sign out |
| Avatars | the provider, or Gravatar (an MD5 hash of the email, as Gravatar requires) | the image request | when a view shows an avatar | Settings → Avatars |
| Remote images in Markdown | the image's host | the image request | only if you turn it on | off by default |
| Update check | Graft's update feed (GitHub Releases) | app version, OS and architecture | at start and every 24 hours | Settings → Updates |
| Crash report | Graft's crash endpoint | the redacted report you previewed | only after you click Send | opt-in, off by default |
| Host-key fingerprints | `api.github.com/meta` | nothing personal | on the first SSH connection to a provider host | none needed |

## What Graft never sends

- No code, file names, commit messages or repository names to the Graft project.
- Nothing to AI services. Agents like Claude Code are your own tools; Graft only answers them locally, on your machine.

## What Graft stores on your machine

| Data | Where |
|---|---|
| Tokens, SSH passphrases | your OS keychain |
| Profiles, settings, workspaces | the app data folder |
| Caches (provider data, avatars, graph layout) | the OS cache folder (safe to delete) |
| Shelves, local history, changelists | inside each repository, under `refs/graft/` and `.git/graft/` |
| Logs | the OS log folder; redacted and rotated |

## Crash reports

- **Preview:** you see the full report before anything is sent.
- **Contents:** the stack trace; Graft, Git, OS and JVM versions; the error code.
- **Redacted before the preview:** paths become `<home>/…`, remote URLs become `<remote>`, tokens and email addresses are removed.
- **Retention:** 90 days, then deleted.

## Remove everything

1. Sign out of each account. This removes its tokens from the keychain.
2. Delete the app data, cache and log folders.
3. Optionally, remove Graft's refs from a repository:
   ```bash
   git for-each-ref --format='%(refname)' refs/graft | xargs -n1 git update-ref -d
   ```
