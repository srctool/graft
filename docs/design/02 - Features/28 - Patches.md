---
tags: [graft, feature, patches]
status: seedling
milestone: G10
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 28 - Patches

## Create

| From | Command | Keeps authorship and message |
|---|---|---|
| Commits (one or a range) | `git format-patch` (one file per commit, optional cover letter) | yes |
| Working changes, a changelist, a selection | `git diff --binary` | no |
| A shelf | `git diff --binary shelf^ shelf` ([Shelves](06%20-%20Shelves.md)) | no |
| A branch comparison | [Compare Branches](21%20-%20Compare%20Branches.md) → export | either |

Save to a file or copy to the clipboard.

## Apply

Drop a `.patch` / `.diff` file on the window, paste from the clipboard, or give a URL to a GitHub commit or PR `.patch`, fetched through the provider. Graft detects the type:

| Type | Applied with |
|---|---|
| Mailbox (from `format-patch`) | `git am --3way`: creates commits with the original author |
| Plain diff | `git apply --3way` into the working tree, or **as a new shelf** without touching the working tree |

A preview of files and hunks comes first, plus a dry run (`git apply --check`). Conflicts use the normal conflict flow, and `git am --continue / --skip / --abort` appear in the banner.

## Out of scope

Sending patches by email (`git send-email`).
