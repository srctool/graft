---
tags: [graft, project, naming]
status: seedling
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 04 - Naming

The name should be short, easy to type as a CLI/launcher command, and fit the family style: **Camouflage** (skin), **Sinew** (connective tissue), single English words with a metaphor.

## Candidates

| Name | Metaphor | Pros | Watch out |
|---|---|---|---|
| **Graft** (chosen 2026-10-18) | grafting joins a branch onto another tree: merge, rebase, cherry-pick | short, verb and noun, Git-y, `graft` CLI | old Git "grafts" feature (deprecated) shares the word; "graft" also means corruption in US English |
| **Canopy** | the view of all branches from above | describes the graph view well, friendly | Canopy is also an older Python IDE and several companies |
| **Trellis** | the frame branches grow along | structure + branches; pairs well with shelves | used by other projects (3D model, UI kits) |
| **Arbor** | tree (Latin) | short, calm | very common name |
| **Grove** | many trees: many worktrees/repos | fits worktrees + submodules | common word |
| **Bough** | a main branch of a tree | rare, distinctive | spelling/pronunciation unclear to many |
| **Rootstock** | the base a graft grows on | strong metaphor | long |

Decision: **Graft** (2026-10-18). **Canopy** stays the backup if an availability check below fails.

## Before choosing

- [ ] GitHub org/repo and `graft` package names free (Homebrew, winget, Flathub, Maven `com.srctool.graft`)
- [ ] Domain (`graft.dev`, `getgraft.app`, …)
- [ ] Trademark search (USPTO, EUIPO, DJKI Indonesia) in class 9 (software)
- [ ] No existing Git GUI with the same name (none found for Graft or Canopy in a quick search on 2026-10-18)
- [ ] Icon idea works at 16 px

Renaming this folder in Obsidian (`projects/graft`) updates every wikilink.
