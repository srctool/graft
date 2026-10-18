---
tags: [graft, feature, profiles]
status: seedling
milestone: G3
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 11 - Profiles

A **profile** is one identity you work as, for example "Personal" and "Work", like GitKraken's profiles. Each repository belongs to one profile, which decides who commits there, which account and SSH key are used. This holds for Graft and for every other tool, including AI agents ([ADR-13](../03%20-%20Project/02%20-%20Decisions.md)). A window's profile only decides its tabs and preferences.

## What a profile holds

| Part | Contents | Stored in |
|---|---|---|
| Git identity | `user.name`, `user.email`, signing format + key (GPG, SSH or none) | `profiles/<id>/profile.json` |
| Provider accounts | GitHub accounts (and later other providers), one default per host | tokens in the OS keychain under `Graft/<profileId>/<host>/<login>` |
| SSH | system SSH (default) or a key managed by Graft for this profile ([SSH](12%20-%20SSH.md)) | key files in `~/.ssh`, passphrase in the keychain/agent |
| Workspace | open tabs, recent repositories, default clone folder, default worktree folder | `profiles/<id>/workspace.json` |
| Preferences | theme, layout, diff options, shortcuts; each can **inherit from Default** | `profiles/<id>/settings.json` |
| Matching rules | which repositories open in this profile automatically | `profile.json` |

There is always a **Default** profile, and it can't be deleted. Everything lives in the app settings directory, never inside a repository.

## Choosing the profile for a repository

When a repository opens, Graft picks a profile in this order:
1. a profile **pinned** to that repository by the user;
2. the first **matching rule**:
   - path: `~/work/**`;
   - remote: `github.com/acme/*`, `ghe.acme.corp/**`;
3. otherwise the **Default** profile (your global git identity), never "whatever the window is on", because other tools can't see the window. If the window is on another profile, Graft asks once: "Pin this repository to <profile>?"

The repo's profile shows as a chip on its tab. Changing it pins the repository (writes the include) so terminal and AI tools follow.

## Applying the identity

**The repository decides its profile, not the app** ([ADR-13](../03%20-%20Project/02%20-%20Decisions.md)). Graft writes each profile's identity, signing key, SSH key and credential helper into a git config file it owns. It then adds `includeIf` rules for the profile's matching rules to a marked block in `~/.gitconfig`, and an `include` to `.git/config` for pinned repos.

Every tool that runs `git` in the folder then uses the same profile, including Claude Code, Cursor, IDEs and the terminal, whether Graft is open or not. Details, the `gh` shim and the Doctor check: [Terminal and AI Agents](13%20-%20Terminal%20and%20AI%20Agents.md).

| Situation | Behavior |
|---|---|
| First profile with rules is created | Graft shows the exact change to `~/.gitconfig` and asks once |
| The repo's local config has its own `user.email` | local config beats the include; a banner offers to remove it so the profile applies |
| User refuses any config writes | fallback: identity passed only on Graft's own commands (`-c`), with a banner that other tools will use the global identity |

## Checks before commit and push

- The commit email isn't a verified email of the profile's GitHub account: warn, because GitHub won't link the commit to the account. Offer the account's `noreply` address.
- The remote's owner matches another profile's rule: warn "You're pushing to acme/* as Personal".

## Actions

Create, rename, duplicate, delete, set icon and color, switch for this repo (tab chip, Find Action, `Mod+Alt+P`, see [Keyboard Shortcuts](../05%20-%20Design/02%20-%20Keyboard%20Shortcuts.md)), open a repository in a different profile, export/import (JSON without any secrets).

## Edge cases

| Case | Decided behavior |
|---|---|
| Two windows on different profiles | allowed; each window has one active profile |
| Same repo open in two profiles | not allowed at once; the second asks to switch the first tab |
| Deleting a profile | removes its tokens from the keychain; asks whether to delete or keep its managed SSH keys; pinned repos fall back to rules |
| Same GitHub account in two profiles | allowed; the token is stored once per profile (revoking it in one doesn't touch the other) |
| Profile identity missing | committing is blocked with "set your name and email in the profile" |
