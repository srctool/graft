---
tags: [graft, feature, profiles, ai]
status: seedling
milestone: G3
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 13 - Terminal and AI Agents

Commits and PRs aren't only made in Graft. AI agents (Claude Code, Cursor, Copilot), IDEs and the terminal run `git`, `ssh` and `gh` themselves. A [profile](11%20-%20Profiles.md) must apply to them too, or they commit and open PRs as the wrong person.

## The problem with an "active profile"

| Tool | What goes wrong |
|---|---|
| GitKraken + Cursor | GitKraken has one global active profile. Cursor's commits use whatever profile is active, not the one the repo belongs to. Switch profile for another repo, and the AI in the first repo now commits as the other identity. |
| Claude Code | runs `git push` non-interactively. If the SSH key has a passphrase that isn't in the agent, or the host key isn't trusted yet, `ssh` wants to prompt, and the agent stalls or keeps asking. |
| `gh pr create` (any agent) | `gh` has one active account per host (`gh auth switch` is global), so PRs get opened from the wrong account. |

## Rule: the repository decides, never the app

**Which profile a repository uses is written into Git's own configuration**, so every tool that runs `git` in that folder gets the same answer, whether Graft is open or not ([ADR-13](../03%20-%20Project/02%20-%20Decisions.md)). Graft has no global "active profile" for identity. The window's profile only decides tabs and preferences.

## How each tool gets the profile

### 1. Git identity, signing and SSH key: managed git config

For each profile, Graft writes one file it fully owns, `<app data>/profiles/<id>/profile.gitconfig`:

```ini
# managed by Graft: profile "Work"
[user]
    name = Dwiki Riyadi
    email = dwiki@acme.com
    signingkey = ~/.ssh/graft_work_ed25519.pub
[gpg]
    format = ssh
[commit]
    gpgsign = true
[core]
    sshCommand = ssh -i ~/.ssh/graft_work_ed25519 -o IdentitiesOnly=yes
[credential "https://github.com"]
    helper =
    helper = graft credential --profile work
```

And one marked block in `~/.gitconfig` turns the profile's matching rules into Git's own conditional includes:

```ini
# >>> graft managed: do not edit between these lines >>>
[includeIf "gitdir:~/work/"]
    path = ~/Library/Application Support/Graft/profiles/work/profile.gitconfig
[includeIf "hasconfig:remote.*.url:git@github.com:acme/**"]
    path = ~/Library/Application Support/Graft/profiles/work/profile.gitconfig
# <<< graft managed <<<
```

| Profile rule | Written as |
|---|---|
| Path (`~/work/**`) | `includeIf "gitdir:~/work/"` (`gitdir/i:` on Windows and macOS) |
| Remote (`github.com/acme/*`) | `includeIf "hasconfig:remote.*.url:…"` (one per URL form: SSH and HTTPS); needs Git 2.36, Graft requires 2.38 |
| Repository pinned to a profile | `[include] path = …` in that repo's `.git/config` |
| Default profile | nothing: the user's plain global `user.*` |

Effects:
- Claude Code, Cursor, the terminal and IDEs all commit as the repo's profile, with its signing key, and push with its SSH key.
- Worktrees share `.git/config` and the main repo's `gitdir`, so they get the same profile.
- Graft itself just runs `git` normally. No `-c` overrides are needed.
- Graft never edits anything outside its marked block. It shows the diff of `~/.gitconfig` before the first write.

### 2. SSH without prompts

Agents can't answer prompts, so the [SSH](12%20-%20SSH.md) wizard prepares everything up front:
- the key's passphrase is stored in the agent and keychain (macOS `UseKeychain` + `AddKeysToAgent`, the Windows OpenSSH agent service, gnome-keyring);
- the host key is verified and already in `known_hosts`;
- optional per-profile **"never prompt"** (`-o BatchMode=yes` in `sshCommand`): an agent gets an immediate, clear error instead of hanging. Graft's own commands still prompt through askpass.

### 3. HTTPS: Graft as credential helper

`graft credential` speaks Git's credential-helper protocol. It returns the token of the profile's account from the keychain, for that host only. Nothing is written to disk.

### 4. `gh` CLI: one gh login per profile

Graft installs an optional **`gh` shim** (first on `PATH` via its shell integration). The shim finds the repo's profile with `git config`, sets `GH_CONFIG_DIR=<app data>/profiles/<id>/gh`, and runs the real `gh`.
- Each profile then has its own gh login, set up by Graft with `gh auth login --with-token` from the profile's account.
- When Claude Code runs `gh pr create`, it opens the PR as the repo's profile, with no global `gh auth switch`.
- Without the shim: `graft gh <args>` does the same explicitly.

### 5. Telling the agent

- `graft whoami` (run in any repo) prints the profile, identity, signing key, SSH key and GitHub account that apply there, and where each comes from (`git config --show-origin`). Agents and humans can check before committing.
- Optional, per repo: Graft adds a short section to a **local, uncommitted** instructions file: `CLAUDE.local.md`, `AGENTS.md` if the repo ignores it, or `.cursor/rules` if ignored. It says the identity comes from git config, not to override `user.name`/`user.email`, and to use `gh` normally. It never writes into committed files without asking.

## Doctor: "what will other tools use here?"

Each repository's settings show a check, also available as `graft doctor`:

| Check | How |
|---|---|
| Identity a terminal would use | `git config --show-origin --get user.email` in that folder |
| Local config overriding the profile | a `user.email` in `.git/config` (local beats global includes) |
| Env overrides | `GIT_AUTHOR_*`, `GIT_COMMITTER_*`, `GIT_SSH_COMMAND` set in the user's shell |
| SSH works non-interactively | `ssh -o BatchMode=yes -T git@github.com` |
| gh account | `gh auth status` through the shim |
| Email verified on the account | provider API |

Green means "an AI agent in this folder commits, pushes and opens PRs as <profile>". Each red row has a fix button.

## AI commits in Graft

- Commits with an AI co-author trailer (`Co-Authored-By: Claude …`, Cursor's, Copilot's) get a small badge on the graph and can be filtered.
- PRs opened by agents look like any other PR; the account is the profile's, as above.

## Edge cases

| Case | Decided behavior |
|---|---|
| User refuses changes to `~/.gitconfig` | Graft falls back to passing the identity on its own commands only, and shows a banner: "Terminal and AI tools here will commit as <global identity>" |
| The user already has their own `includeIf` blocks | left alone; Doctor shows which one wins (`--show-origin`) |
| A repo matches two profiles' rules | the later include wins in Git; Graft orders its block (path rules first, then remote rules) and warns about the overlap when the rule is created |
| Fresh `git clone` by an agent | remote rules apply as soon as `origin` exists; path rules from the clone's location |
| Submodules | path rules follow the parent's location; remote rules follow the submodule's own remote; Doctor shows the result |
| Profile deleted | its includes are removed from the block and from pinned repos, after showing them |
| Shim and an existing `gh` alias or wrapper | the shim always calls the next `gh` on `PATH` |
