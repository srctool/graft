---
tags: [graft, foundations, accessibility]
status: seedling
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 05 - Accessibility

Accessibility is part of every feature, and verified in G8. The graph needs its own design because it's drawn on a canvas, which screen readers can't see without help.

## Graph

- **Each row exposes semantics** (`Modifier.semantics`): "Commit a1b2c3d, Fix login timeout, by Dwiki Riyadi, 2 hours ago, branches main and origin/main, merge of 2 parents, signed". Lane lines are decorative and hidden.
- **Keyboard:**

  | Keys | Moves to |
  |---|---|
  | ↑ / ↓ | previous / next row |
  | Alt+↓ / Alt+↑ | first parent / child |
  | Alt+→ | other parents of a merge |
  | Enter | commit details |
  | Ctrl/Cmd+F | search |
  | `[` / `]` | previous / next ref |

- **Colors aren't the only signal:** lanes also differ by marker shape (filled, hollow, dashed for shelves and stashes), and labels name the branch.

## Diffs

- Added and removed lines carry `+` / `−` gutter markers, not only green and red.
- Screen readers read "added line 12: …".
- A color-blind-safe diff palette is an option.

## General

- Every action reachable by keyboard and through the command palette; focus always visible; logical tab order per pane.
- WCAG 2.2 AA contrast in all themes, plus a high-contrast theme.
- Follows the OS text size, reduce motion and high-contrast settings.
- No information only on hover: tooltips are also reachable by focus.

## Platforms and testing

- Compose Desktop exposes accessibility to VoiceOver (macOS) and to NVDA/Narrator (Windows). Linux (Orca) support is weaker; it's tracked as a known limitation.
- CI checks semantics trees with Compose UI tests: every interactive element must have a label and role.
- Before each release: a manual pass with VoiceOver and NVDA.
