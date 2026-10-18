---
tags: [graft, project, docs]
status: seedling
milestone: G7
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 05 - User Documentation

The public docs site for people who **use** Graft. These design notes are for people who **build** it, and the two never mix. The site is written in the repository under `docs/site/` (Docusaurus), versioned with releases, and published to GitHub Pages.

## Sections

| Section | Pages |
|---|---|
| **Get started** | install (per OS, including "right-click → Open" for unsigned macOS builds); first run; open or clone a repo; your first commit and push |
| **Everyday work** | commit graph; branches and tags; commit and stage; pull, push, fetch; resolve conflicts; undo anything |
| **Graft's extras** | shelves; changelists; local history; worktrees; submodules; stacks; compare branches; fixup and absorb |
| **GitHub** | sign in; pull requests; reviews; issues; My Work; notifications; CI |
| **Profiles and SSH** | profiles and rules; how Graft writes `~/.gitconfig` (and how to undo it); SSH setup; several GitHub accounts on one machine |
| **AI agents** | using Graft with Claude Code; with Cursor; the `graft` CLI; the MCP server; agent worktrees and review; what agents can and can't do through Graft |
| **Safety** | commit guards; recovering lost work (local history, reflog); what Graft never does |
| **Reference** | settings; keyboard shortcuts; CLI commands (generated from `graft --help`); error codes (generated from the registry); glossary |
| **Coming from…** | GitKraken; GitHub Desktop; JetBrains IDEs (shelves and changelists map one to one); the git CLI |
| **Troubleshooting** | "Permission denied (publickey)"; credential prompts; "dubious ownership"; slow on large repos; Linux keychain missing; Windows long paths |
| **Privacy** | what Graft sends and when ([Privacy](07%20-%20Privacy.md)) |

## Rules

- **Task-first:** each page answers "how do I…", with steps, then the details.
- **Generated reference pages:** CLI help, error codes, settings and shortcuts come from the code, so they can't drift.
- **Screenshots:** taken by a script from the screenshot tests, in light and dark, so they update with the UI.
- **Languages:** English first; Indonesian for Get started and Everyday work from 1.0.
- **Every error code** in the app links to its troubleshooting entry.

## Milestones

| When | Content |
|---|---|
| G7 public beta | Get started, Everyday work, Profiles and SSH, Troubleshooting, Privacy |
| G8 1.0 | everything above except AI agents |
| G9 1.1 | AI agents section |
