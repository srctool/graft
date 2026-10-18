---
tags: [graft, feature, cli, ai]
status: seedling
milestone: G3 (core), G9 (agent commands)
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 31 - CLI

`graft` is a command-line tool for you, for scripts, and for AI agents. It does what only Graft can do (profiles, shelves, changelists, local history, guards, review feedback, stacks). For everything else, agents keep using plain `git` and `gh`, which already follow the repo's profile ([Terminal and AI Agents](13%20-%20Terminal%20and%20AI%20Agents.md)).

## How it runs

- **A separate small binary** sharing the `core-*` modules with the app, compiled with GraalVM native-image so it starts in milliseconds, not JVM seconds ([ADR-19](../03%20-%20Project/02%20-%20Decisions.md)).
- **Works without the app.** If the app is running, the CLI also tells it what changed (local socket: a Unix domain socket, or a named pipe on Windows), so the UI updates at once.
- Installed on `PATH` by [Onboarding](22%20-%20Onboarding.md).

## Conventions

| Convention | Meaning |
|---|---|
| `-C <path>` | run in another folder, like `git -C`; default: current folder |
| `--json` | machine-readable output on stdout (stable schema, versioned); errors as JSON on stderr |
| `--yes` | skip the confirmation for destructive commands (otherwise they ask, or fail when not on a TTY) |
| Exit codes | `0` ok, `1` error, `2` usage error, `3` blocked by a guard, `4` needs confirmation, `5` conflicts left to resolve |
| Profile | always the repo's profile; there's no `--profile` override for identity |

## Commands

### App and repositories
| Command | Does |
|---|---|
| `graft` / `graft open [path]` | open the repo in the app |
| `graft clone <url> [dir]` | clone with the matching profile, then open |

### Profile and checks
| Command | Does |
|---|---|
| `graft whoami` | profile, identity, signing key, SSH key, account for this folder, with sources |
| `graft doctor [--fix]` | what terminal tools will use here; `--fix` applies the safe fixes |
| `graft guard --staged` / `--range A..B` | [commit guards](19%20-%20Commit%20Guards.md); exit 3 when blocked |

### Shelves
| Command | Does |
|---|---|
| `graft shelf list` | id, name, base, files, age |
| `graft shelve -m <name> [paths…] [--keep]` | shelve all or some paths; `--keep` leaves them in the working copy |
| `graft shelf show <id>` | files and diff |
| `graft unshelve <id> [paths…] [--apply]` | restore all or some; `--apply` keeps the shelf |
| `graft shelf drop <id>` | to the recycle bin |

### Changelists
| Command | Does |
|---|---|
| `graft changelist list` | lists with their files and hunks |
| `graft changelist new <name> [--active]` | create; optionally make active |
| `graft changelist move <name> <paths…>` | move files into a list |
| `graft changelist active <name>` | set the active list |
| `graft changelist commit <name> [-m msg]` | commit exactly that list |

### Local history
| Command | Does |
|---|---|
| `graft snapshot [-m label] [--quiet]` | take a snapshot now |
| `graft history list [path] [--since 1h]` | snapshots, optionally for one file |
| `graft history restore <path> --before 10m` / `--at <snapshot>` | restore a file (a snapshot of the current state is taken first) |
| `graft history deleted` | files that existed in a snapshot but are gone |

### Review and agents
| Command | Does |
|---|---|
| `graft review feedback [--json]` | the comments you left in [review mode](23%20-%20Agent%20Worktrees%20and%20Review.md) for this worktree, as `path:line: comment` |
| `graft review resolve <id>` | mark a comment handled |
| `graft agent new <task> [--from-issue N] [--launcher claude]` | create an agent worktree and start the agent |

### Stacks
| Command | Does |
|---|---|
| `graft stack` | show the stack of the current branch |
| `graft stack branch <name>` | new branch on top |
| `graft stack restack` / `sync` / `submit` | as in [Stacked Branches](25%20-%20Stacked%20Branches.md) |

### Plumbing (called by other programs)
| Command | Called by |
|---|---|
| `graft credential <get\|store\|erase>` | Git (credential helper) |
| `graft gh <args>` / the `gh` shim | you or agents, transparently |
| `graft askpass` | Git and SSH, for Graft's own commands |
| `graft mcp` | AI clients ([MCP Server](32%20-%20MCP%20Server.md)) |

## What the CLI never does

No command changes profiles, edits `~/.gitconfig`, prints or exports tokens, or rewrites history. Those stay in the app, where a human sees the change.
