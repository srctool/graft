---
tags: [graft, foundations, ui]
status: seedling
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 06 - UI Design

**GitKraken's layout, JetBrains' behavior, Fluent's look in Atom One colors.**
- **Layout** like GitKraken: refs on the left, the graph in the center, details on the right, and a toolbar with the everyday actions.
- **Behavior** like a JetBrains IDE: tool windows, Search Everywhere, speed search, the diff and merge viewers ([ADR-20](../03%20-%20Project/02%20-%20Decisions.md)).
- **Look:** Microsoft's Fluent 2 at desktop density, through Camouflage's Fluent Skin: gently rounded corners, neutral surfaces, shadows on overlays, a double-stroke focus ring. The colors are Atom's **One Light** and **One Dark** ([ADR-29](../03%20-%20Project/02%20-%20Decisions.md)).

## Toolkit: Camouflage with the Fluent skin

Graft's UI is built on Camouflage with its Fluent skin ([ADR-22](../03%20-%20Project/02%20-%20Decisions.md), [ADR-29](../03%20-%20Project/02%20-%20Decisions.md)). Camouflage draws on Compose foundation; Material 3 isn't a dependency. Screens use Camouflage components directly. `:module:core:ui` holds Graft's theme and the components only Graft needs, and `:app:desktop` sets up the Camouflage provider ([ADR-24](../03%20-%20Project/02%20-%20Decisions.md)).

**Why Fluent.**
- It's a desktop-first design language, calm on dense screens: neutral surfaces, subtle hover steps and a brand color used sparingly.
- Seen side by side with Carbon on Graft's own screens, the author preferred it.
- Camouflage ships it (0.2.0), so Graft's needs feed back into Camouflage.

**Why Atom One colors.** One Light and One Dark are familiar from editors, readable for long sessions, and give one palette to the UI, the graph lanes and syntax highlighting. They replace Fluent's own neutrals and brand color, through the theme's color roles and the skin's tokens.

JetBrains' Jewel library was considered and not chosen. Its API still changes between releases, it ties Graft to IntelliJ Platform versions, and Camouflage has no IntelliJ skin ([ADR-20](../03%20-%20Project/02%20-%20Decisions.md)).

### Fluent at Graft's density

| Part | Graft |
|---|---|
| Controls | **24 px** (toolbars, panels, inline) and **32 px** (dialogs, forms); the skin's density is `Medium` (32 px) |
| One height per line | controls on one line (a field, a button, a select, a segmented control) have the same height, top and bottom, also while a field shows an error |
| Button styles | Fluent's: primary (accent fill), default (neutral with a border), outline, subtle (ghost), link; danger for destructive actions. An action that only navigates is a link, so it still looks clickable |
| Text fields | outlined, with the accent on the bottom edge on focus; label above |
| Tree and list rows | **24 px** rows in panels, trees and lists (28 px in comfortable density); 32 px menu items |
| Type | Fluent's ramp in **Inter**: body 14, caption 12, subtitle 16 semibold, title 20 semibold. Dense panels and the graph use a 13 px Graft step |
| Shapes | **4 px** on controls, 8 px on dialogs, flyouts and cards; pills for tags (ref chips) and toggles |
| Surfaces | `Solid`: neutral fills, shadows on menus, popovers and dialogs. Acrylic and Mica wait |
| Feedback | subtle hover and pressed background steps; no ripple |
| Focus | Fluent's **double stroke**, shown on keyboard focus; Graft's own components draw the same ring |
| Colors | **Atom One**, below, including the skin's tokens: no Fluent blue or gray shows |
| Tabs | line tabs with an accent underline |
| Scrollbars | thin overlay scrollbars that widen on hover |

### Atom One colors

Graft Light uses **One Light**, Graft Dark uses **One Dark** (Atom, MIT). Values are used as published where they pass WCAG 2.2 AA; where a color carries text and doesn't, a darker (light theme) or lighter (dark theme) step of the same hue is used. Camouflage's theme validator pins the final color roles, and Graft's tests pin the skin's tokens ([Accessibility](05%20-%20Accessibility.md)).

| Role | One Dark | One Light | Note |
|---|---|---|---|
| Window background | `#282c34` | `#fafafa` | |
| Panels, sidebars, the header | `#21252b` | `#eaeaeb` | |
| Raised layer | `#2c313a` | `#f0f0f1` | |
| Selection | `#3e4451` | `#e5e5e6` | |
| Text | `#abb2bf` | `#383a42` | both pass |
| Secondary text | lighter than the comment gray `#5c6370` (about `#9198a4`) | darker than `#a0a1a7` | the published grays fail AA |
| Accent, links, primary button | blue `#61afef`, with dark text on the button | blue `#4078f2`, a darker step for text and the button | light blue is 3.9:1 on the background |
| Error, success, warning, info | red `#e06c75` (a lighter step for text), green `#98c379`, yellow `#e5c07b`, cyan `#56b6c2` | red `#e45649`, green `#50a14f`, orange `#986801`, cyan `#0184bc`, darker steps for text | |

The same palette gives the graph's lane colors (see Graph style) and the diff's syntax highlighting (One's syntax colors).

### What Graft builds on top of Camouflage

Camouflage covers trees (`CamoTree`), tables (`CamoDataTable`), menus, popovers, side panels and the rest of its inventory. Graft adds: split panes with draggable dividers, tool windows with edge stripes, speed search in lists, trees and popups, compact closable editor tabs, the command palette (Search Everywhere, Find Action), menu submenus, resizable and hideable table columns, desktop scrollbars, and the git widgets (graph, diff, merge). All but the git widgets are proposed to Camouflage ([camouflage#71–#78](https://github.com/srctool/camouflage/issues)); until one ships, Graft builds it in `:module:core:ui` on Camouflage tokens, shaped like the proposal.

### Title bar

| OS | 1.0 |
|---|---|
| macOS | full-height content with a transparent title bar (the native traffic-light buttons stay) so the toolbar sits in the title bar, like GitKraken and JetBrains |
| Windows, Linux | native title bar, with the toolbar as the first row below it. A custom title bar would lose native snapping and window behavior, so it comes later only if it can keep them |

### Assets

| Asset | Source |
|---|---|
| UI font | **Inter** (OFL-1.1), bundled on every OS: Camouflage skins never bundle fonts, so the app does |
| Code and diff font | JetBrains Mono, 13 px (OFL), ligatures off by default; IBM Plex Mono as an option |
| Icons | **Carbon's icon set** (`@carbon/icons`, IBM, Apache-2.0), 16 px in panels and 20 px in the toolbar. Git icons it lacks (shelf, worktree, stack, stash, and any of branch, merge, commit, pull request, tag, fork it turns out not to have) drawn on its 16/20/32 grid |
| Themes | Graft Light (One Light), Graft Dark (One Dark), High contrast (follows the OS high-contrast colors when the OS reports it) ([Accessibility](05%20-%20Accessibility.md)) |

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
- **Colors:** an 8-color lane palette from Atom One (blue, purple, green, red, orange, yellow, cyan, and the text gray), checked against both themes, and always paired with labels and marker shapes ([Accessibility](05%20-%20Accessibility.md)).
- **Ref labels** like JetBrains' log tags: tags (pills) with a branch, tag or remote icon, plus the PR and CI status chips.
- **Rows:** 26 px tall, 22 px in compact mode.

## `:module:core:ui` components

Screens use Camouflage's components directly (`CamoButton`, `CamoTree`, `CamoDataTable`, `CamoTabs`, `CamoDialog`, …), drawn by the Fluent skin with Graft's theme ([ADR-24](../03%20-%20Project/02%20-%20Decisions.md)). `:module:core:ui` holds only what Graft adds:

| Group | Components |
|---|---|
| Until Camouflage has them ([camouflage#71–#78](https://github.com/srctool/camouflage/issues)) | `SplitPane`, `ToolWindow` with edge stripes, speed search for lists, trees and popups, document tabs, the command palette (Search Everywhere, Find Action), menu submenus, desktop scrollbars, resizable table columns |
| Git-specific | `GraphCanvas`, `DiffViewer`, `MergeViewer`, `RefChip`, `AvatarStack`, `StatusDot` |
| Setup | the Graft theme (light, dark, high contrast), the density, fonts and icons, the `Localizer` |
