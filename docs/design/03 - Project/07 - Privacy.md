---
tags: [graft, project, privacy]
status: seedling
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 07 - Privacy

Graft collects nothing about its users. The repository's `PRIVACY.md` and the docs site's Privacy page are written from this note. Every network request Graft makes is listed here. If a new feature adds one, this note is updated in the same PR.

## What Graft sends, and when

| Request | To | Sends | When | Off switch |
|---|---|---|---|---|
| Git fetch, pull, push, clone | your remotes | what Git sends (git does this, not Graft) | when you or background fetch run it | background fetch setting |
| Provider API (GitHub) | the provider host | your token and the requests for the data shown | while provider views are open; polling as in [Settings Reference](../01%20-%20Foundations/08%20-%20Settings%20Reference.md) | sign out |
| Avatars | provider, or Gravatar (an MD5 hash of the email, as Gravatar requires) | the image request | when a view shows an avatar | Settings → Avatars |
| Remote images in Markdown | the image's host | the image request | only if turned on | off by default |
| Update check | Graft's update feed (GitHub Releases) | app version, OS and architecture | at start and every 24 h | Settings → Updates |
| Crash report | Graft's crash endpoint | the redacted report you previewed | only after you click Send | opt-in, off by default |
| Host-key fingerprints | `api.github.com/meta` | nothing personal | first SSH connection to a provider host | none needed |

## What Graft never sends

- No analytics, telemetry, usage statistics or identifiers.
- No code, file names, commit messages or repository names to the Graft project.
- Nothing to AI services. Agents like Claude Code are your own tools; Graft only answers them locally ([MCP Server](../02%20-%20Features/32%20-%20MCP%20Server.md)).

## What Graft stores locally

| Data | Where |
|---|---|
| Tokens, SSH passphrases | OS keychain |
| Profiles, settings, workspaces | app data folder |
| Caches (provider data, avatars, graph layout) | OS cache folder (safe to delete) |
| Shelves, local history, changelists | inside each repository ([Storage Formats](../04%20-%20Development/06%20-%20Storage%20Formats.md)) |
| Logs | OS log folder; redacted, rotated |

**Remove everything:**
1. Sign out of each account (this removes its tokens).
2. Delete the app data, cache and log folders.
3. Optionally, remove Graft's refs from a repository with `git for-each-ref --format='%(refname)' refs/graft | xargs -n1 git update-ref -d`.

## Crash reports

- **Preview:** shown in full before sending.
- **Contents:** stack trace, Graft, Git, OS and JVM versions, the error code.
- **Redacted before the preview:** paths become `<home>/…`, remote URLs become `<remote>`, tokens and emails are removed.
- **Retention:** 90 days, then deleted.
