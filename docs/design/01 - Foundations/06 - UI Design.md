---
tags: [graft, foundations, ui]
status: seedling
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 06 - UI Design

**GitKraken's layout, JetBrains' behavior, Microsoft's Fluent 2 look.**
- **Layout** like GitKraken: refs on the left, the graph in the center, details on the right, and a toolbar with the everyday actions.
- **Behavior** like a JetBrains IDE: tool windows, Search Everywhere, speed search, the diff and merge viewers ([ADR-20](../03%20-%20Project/02%20-%20Decisions.md)).
- **Look:** Fluent 2 at desktop density: neutral surfaces, 4 px corners, subtle hover, a sparing brand color. It follows Camouflage's Fluent Skin, so Graft can switch to Camouflage later without a visual change ([ADR-21](../03%20-%20Project/02%20-%20Decisions.md)).

## Toolkit: Material 3 with a Fluent theme

Graft uses Compose Material 3 underneath and restyles it as **Fluent 2** in `ui-kit`. Material's defaults are made for touch screens: 40–56 dp controls, pill-shaped buttons, ripples, tinted surfaces. Fluent 2 is Microsoft's design system, made for desktop apps, and it's calm, flat and compact.

**Why Fluent.**
- It's a desktop design language, and it's documented as a full token set (neutral ramp, brand ramp, shadows, type ramp, sizes).
- Camouflage already has a Fluent skin designed. The Graft theme uses **the same token mapping** as that skin, so Graft is the first real app to exercise it, and the later switch to Camouflage changes no visuals.
- It's not tied to a vendor library: no Fluent UI component library is used, only the published design.

JetBrains' Jewel library was considered and not chosen. Its API still changes between releases, it ties Graft to IntelliJ Platform versions, and Camouflage has no IntelliJ skin ([ADR-20](../03%20-%20Project/02%20-%20Decisions.md)).

### What the Graft theme changes

| Material default | Graft (Fluent 2, desktop density) |
|---|---|
| 48 dp minimum touch target | `LocalMinimumInteractiveComponentSize` lowered; pointer-sized hit areas |
| Buttons 40 dp tall, fully rounded | Fluent sizes: **small 24 px** (toolbars, panels, inline), **medium 32 px** (dialogs, forms); 4 px radius |
| Button styles | Primary = brand fill; Default = `neutralBackground1` + `neutralStroke1` border; Subtle = transparent until hover; Outline; Link |
| Text fields 56 dp (`OutlinedTextField`) | 24 / 32 px, 4 px radius, **bottom-accent stroke** that turns brand-colored on focus, label above: `BasicTextField` with a custom decoration box, inside `GraftTextField` |
| List, tree and menu items 48–56 dp | 24 px rows in panels and the graph (28 px in comfortable density); 32 px menu items |
| Type scale (body 16 sp) | Fluent ramp: caption1 12, body1 14, subtitle2 16 semibold, subtitle1 20, title3 24. Dense panels and the graph use a 13 px Graft density step between caption1 and body1 |
| Shapes (extra-large corners) | 4 px controls, 8 px cards and dialogs |
| Tonal elevation, `surfaceTint` | off: neutral surfaces (`neutralBackground1..6`) with 1 px `neutralStroke2` dividers; Fluent shadows (shadow4 cards, shadow16 popups, shadow64 dialogs) |
| Ripple on press | none: **subtle** feedback, where hover and pressed move the background one or two neutral steps |
| Focus | Fluent's **double stroke** (2 px dark outer + 1 px light inner), shown on keyboard focus |
| Color scheme from a seed color | Fluent's neutral ramp plus a 16-step brand ramp from one brand color (Fluent blue `#0F6CBD` until Graft has its own); danger, success and warning palettes |
| Tabs | Fluent TabList: text tabs with a 2 px brand underline |
| Menus and popups | flyouts, radius 4, shadow16; **Acrylic** (frosted) background later, solid in 1.0 |
| Scrollbars | thin overlay scrollbars that widen on hover |

### What Material doesn't have (built in `ui-kit`)

Tree with speed search, table with resizable and hideable columns, split pane with draggable dividers, tool windows with edge stripes, compact closable tabs, popups with speed search, and the git widgets (graph, diff, merge).

### Title bar

| OS | 1.0 |
|---|---|
| macOS | full-height content with a transparent title bar (the native traffic-light buttons stay) so the toolbar sits in the title bar, like GitKraken and JetBrains |
| Windows, Linux | native title bar, with the toolbar as the first row below it. A custom title bar would lose native snapping and window behavior, so it comes later only if it can keep them |
| Windows 11, later | **Mica** window backdrop (wallpaper-tinted, via the DWM API), as in Camouflage's Fluent skin; solid elsewhere |

### Assets

| Asset | Source |
|---|---|
| UI font | the platform font, as in Camouflage's Fluent skin: **Segoe UI Variable** on Windows, the system font on macOS (SF Pro); Inter (OFL, bundled) on Linux. Segoe and SF are never bundled, since they aren't licensed for redistribution |
| Code and diff font | JetBrains Mono, 13 px (OFL), ligatures off by default |
| Icons | **Fluent UI System Icons** (Microsoft, MIT license), Regular style, 16 px in panels and 20 px in the toolbar; it already includes branch, merge and many dev icons. Missing git icons (shelf, worktree, stack, stash) drawn in the same style |
| Themes | Graft Light, Graft Dark (from Fluent's light and dark tokens), High contrast (follows Windows high-contrast colors when the OS reports it) ([Accessibility](05%20-%20Accessibility.md)) |

## Main window

```
┌────────────────────────────────────────────────────────────────────────────────────┐
│ ● ● ●  [app ▾] [main ▾]  ↶ ↷   ⬇Pull▾ ⬆Push▾ ⑂Branch  Shelve▾  Stash▾  (Work) 🔍 🔔 │ title bar = toolbar
├────────────────────────────────────────────────────────────────────────────────────┤
│ Home │ app × │ app: agent/login × │ api ×                                          │ repo tabs
├──┬───────────────────┬──────────────────────────────────────────┬──────────────┬──┤
│▤ │ LOCAL         3   │ BRANCH/TAG    GRAPH  MESSAGE      AUTHOR │ WIP          │▤ │
│⑂ │  ● main      ↑1   │               ○      // WIP (4 files)    │ Changelists  │✎ │
│☰ │    feature/login  │ main ⇄ origin ●      Fix login timeout DR│  Default  2  │◷ │
│▣ │ REMOTE        2   │               ●      Add retry      🤖 DR│  Agent    2  │  │
│⚙ │ WORKTREES     2   │ v1.4 ─────────●──╮   Merge #42        DR │──────────────│  │
│  │ SHELVES       1   │               │  ●   Update deps   bot   │ Summary      │  │
│  │ STASHES       0   │               ●──╯   ...                 │ Description  │  │
│  │ PULL REQUESTS 4   │                                          │ [Commit ▾]   │  │
│  │ ISSUES · STACKS   │                                          │              │  │
│  │ AGENTS · SUBMODS  │                                          │              │  │
├──┴───────────────────┴──────────────────────────────────────────┴──────────────┴──┤
│ Git log │ Operations │ CI                                                          │ bottom tool window
├────────────────────────────────────────────────────────────────────────────────────┤
│ ⑂ main ↑1  ·  Work (dwiki@acme.com)  ·  ✓ guards  ·  fetching… ▮▮▯  ·  API 92%      │ status bar
└────────────────────────────────────────────────────────────────────────────────────┘
```

| Area | Content | Behaves like |
|---|---|---|
| **Toolbar** (in the title bar on macOS) | repo and branch selectors; Undo/Redo; Pull, Push, Branch, Shelve, Stash with dropdowns; the repo's profile chip; Search Everywhere; notifications | GitKraken toolbar |
| **Repo tabs** | Home ([Repositories](../02%20-%20Features/17%20-%20Repositories%20and%20Workspaces.md), [My Work](../02%20-%20Features/18%20-%20My%20Work%20and%20Notifications.md)), one tab per repo or worktree | GitKraken tabs |
| **Left panel** | collapsible sections with counts: Local, Remote, Worktrees, Shelves, Stashes, Pull Requests, Issues, Stacks, Agents, Tags, Submodules. Each item has hide, solo and pin | GitKraken left panel, rendered as a JetBrains tool window with speed search |
| **Center** | the commit graph as a table: Branch/Tag, Graph, Message, Author, Date, SHA columns (resizable, hideable). Diffs, PRs, issues, compare and bisect open **in the center** with a breadcrumb back to the graph | GitKraken center |
| **Right panel** | depends on the selection: **WIP** → changes (Staged/Unstaged or changelists) and the commit box; **commit** → message, author, signature, files as tree or list; **PR** → summary and checks | GitKraken right panel |
| **Tool window stripes** | thin icon bars on both edges toggle and move panels; panels can be hidden, resized, or dragged to another side | JetBrains New UI |
| **Bottom tool window** | Git log (every command run, ADR's "show the command"), Operations ([op log and reflog](../02%20-%20Features/16%20-%20Reflog%20and%20Recovery.md)), CI logs; later the terminal | JetBrains Git console |
| **Status bar** | branch and ahead/behind; profile and email; guards on/off; background tasks with progress; API rate limit | JetBrains status bar |

**Layouts:** the window remembers its layout per repository. **Focus mode** (`Ctrl/Cmd+Shift+F12`, as in JetBrains) hides every panel except the center.

## JetBrains behaviors to copy

| Behavior | In Graft |
|---|---|
| **Search Everywhere** (double Shift) | one search box for repos, branches, tags, commits (message or SHA), files, PRs, issues, shelves and actions |
| **Find Action** (`Ctrl/Cmd+Shift+A`) | every command by name, with its shortcut shown |
| **Speed search** | start typing in any list or tree (branches, files, shelves) to filter it, no search field needed |
| **Popups** | branch switcher and dropdowns as JetBrains popups: keyboard-first, with grouped sections (Recent, Local, Remote) |
| **Diff viewer** | side-by-side or unified; colored connectors between panes; `>>` / `<<` arrows in the gutter to move a change from one side to the other; jump to the next or previous change (F7 / Shift+F7); word-level highlights |
| **Merge tool** | the JetBrains three-pane merge: theirs, **result**, ours, with accept/ignore arrows per change, "accept non-conflicting changes", and a magic-wand "resolve simple conflicts" |
| **Notifications** | balloons bottom-right that fade, collected in a notifications tool window |
| **Settings** | a dialog with a searchable tree on the left; every setting shows its scope (app, profile, repo) |
| **Keymap** | JetBrains defaults; presets for GitKraken and VS Code shortcuts; fully remappable |
| **Density** | the New UI's compact mode as an option |

## GitKraken behaviors to copy

- **Drag and drop on the graph:** drag a branch onto another for merge or rebase; drag onto a remote to push; drag a commit onto a branch to cherry-pick.
- **WIP node** at the top of the graph that opens the changes panel.
- **Avatars** in the graph and author column, from the provider or Gravatar (can be turned off).
- **Undo / Redo** buttons always visible in the toolbar ([op log](03%20-%20Git%20Engine.md)).
- **Solo / hide branches** from the left panel to declutter the graph.
- **Fuzzy finder** for repos and branches, merged into Search Everywhere.

## Graph style

- **Lanes:** 2 px lines with rounded corners for merges and forks; 10 px commit dots. Hollow dots for stashes, dashed for shelves and snapshots, a ring for the WIP node.
- **Colors:** an 8-color lane palette tuned for Graft Light and Dark, color-blind-safe, and always paired with labels ([Accessibility](05%20-%20Accessibility.md)).
- **Ref labels** like JetBrains' log tags: small rounded chips with a branch, tag or remote icon, plus the PR and CI status chips.
- **Rows:** 26 px tall, 22 px in compact mode.

## `ui-kit` components

Every screen is built only from these (lint-enforced). Most wrap a Material 3 component styled with the Fluent tokens; trees, tables, split panes, tool windows and the git widgets are Graft's own. The toolkit underneath can change later ([ADR-9](../03%20-%20Project/02%20-%20Decisions.md), [ADR-20](../03%20-%20Project/02%20-%20Decisions.md)):

| Group | Components |
|---|---|
| Actions | `GraftButton` (default, primary, outlined), `GraftIconButton`, `GraftSplitButton` (Pull ▾), `GraftToolbar` |
| Inputs | `GraftTextField`, `GraftTextArea` (commit message), `GraftCheckbox`, `GraftDropdown`, `GraftSearchField` |
| Lists | `GraftList`, `GraftTree` (both with speed search and virtualization), `GraftTable` (resizable columns) |
| Containers | `GraftToolWindow`, `GraftTabs`, `GraftSplitPane`, `GraftPopup`, `GraftDialog`, `GraftBanner` |
| Feedback | `GraftNotification`, `GraftProgress`, `GraftTooltip`, `GraftBadge` / `GraftChip` |
| Git-specific | `GraphCanvas`, `DiffViewer`, `MergeViewer`, `RefChip`, `AvatarStack`, `StatusDot` |
