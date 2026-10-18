---
tags: [graft, feature, onboarding]
status: seedling
milestone: G1 (basic), G3 (full)
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 22 - Onboarding

The first-run setup ties together pieces that exist elsewhere. Every step can be skipped, and the whole thing can be re-run from Settings.

| Step | What happens | Details |
|---|---|---|
| 1. Git | check `git --version` ≥ 2.38. If it's missing or old: macOS → Xcode Command Line Tools or the bundled Git; Windows → the bundled Git for Windows; Linux → the package-manager command to run | [Git Engine](../01%20-%20Foundations/03%20-%20Git%20Engine.md) |
| 2. Default profile | name and email, prefilled from the global git config | [Profiles](11%20-%20Profiles.md) |
| 3. More profiles (optional) | e.g. Work, with a path or remote rule; shows the `~/.gitconfig` change before writing it | [Terminal and AI Agents](13%20-%20Terminal%20and%20AI%20Agents.md) |
| 4. SSH | detect existing keys or run the wizard, per profile | [SSH](12%20-%20SSH.md) |
| 5. GitHub | device-flow login per profile | [Provider Model](../01%20-%20Foundations/04%20-%20Provider%20Model.md) |
| 6. Command line | put `graft` on `PATH` (`/usr/local/bin` or `~/.local/bin` symlink; a Windows PATH entry) | [Repositories](17%20-%20Repositories%20and%20Workspaces.md) |
| 7. Shell integration (optional) | the `gh` shim for per-profile gh logins | [Terminal and AI Agents](13%20-%20Terminal%20and%20AI%20Agents.md) |
| 8. AI tools (optional) | detect Claude Code and Cursor. Offer: add the Graft MCP server (`claude mcp add --scope user graft -- graft mcp`, Cursor's `mcp.json`), local-history snapshot hook, commit-guard hook, agent launcher presets | [Local History](14%20-%20Local%20History.md), [Commit Guards](19%20-%20Commit%20Guards.md), [Agent Worktrees](23%20-%20Agent%20Worktrees%20and%20Review.md), [MCP Server](32%20-%20MCP%20Server.md) |
| 9. Look | theme, density, diff style | |
| 10. Repositories | clone, open, or scan a folder | [Repositories](17%20-%20Repositories%20and%20Workspaces.md) |

## Import (later)

Recent repositories and profiles from GitKraken, GitHub Desktop, Sourcetree and Fork, read from their settings files (no secrets).
