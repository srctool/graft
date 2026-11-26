# Camouflage 0.2.0 swap: Graft's stopgaps against what shipped

**Status: approved 2026-11-26 (drafted the same day). No task started. Density is decided (`Sm`); the other decisions in section 5 are still open.**

**Updated 2026-11-26** after [PR #184](https://github.com/srctool/graft/pull/184): Local History already uses `CamoSegmentedControl`, so Task 4 covers Compare and the diff screen only, and the popover ruling is already corrected.

**Goal:** per ADR-22, replace Graft's stopgap components with Camouflage's where that loses nothing the design asks for, and record why the rest stay.

**Method:** read Graft's components and callers, and the `camouflage-core` and `camouflage-skin-carbon` 0.2.0 **sources jars** from the Gradle cache. Nothing was run, so every behavior below is read from source.

**Source notes:** ADR-22, ADR-24; UI Design (components, density, "Layouts"); Storage Formats (`state.json`); Keyboard Shortcuts; UX Writing (toasts, localization); Accessibility.

## 1. Recommendation

| Stopgap | Camouflage 0.2.0 | Verdict | Task |
|---|---|---|---|
| (density by modifiers) | `carbonTheme(density = …)` | **Adopt, first** | 1 |
| `SpeedSearch` matcher, state, highlight | `CamoMatcher`, `CamoSpeedSearchState`, `CamoHighlightedText` | **Swap**; keep Graft's field | 2 |
| `Scrollbars` | `CamoScrollbar` | **Swap**, if the drag test passes | 3 |
| segmented buttons in screens | `CamoSegmentedControl` | **Swap** | 4 |
| commit description box | `CamoTextField(singleLine = false)` | **Swap**, if it keeps its height | 5 |
| `DockLayout` | `CamoDockLayout` | **Leave** | – |
| `SplitPane` | `CamoSplitPane` | **Leave** (only `DockLayout` uses it) | – |
| `DocumentTabs` | `CamoDocumentTabs` | **Leave** until its words can be translated | – |
| `CommandPalette` | `CamoCommandPalette` | **Leave** | – |
| `SearchPalette` | none that fits | **Leave** | – |
| `TableColumns` | `CamoDataTable` + `CamoColumnState` | **Leave** | – |
| breadcrumbs in screens | `CamoBreadcrumb` | **Leave** | – |
| toast bars | `CamoMessenger` | **Leave** | – |
| Options section in the commit box | `CamoPopover` | **Leave** (ruling corrected in PR #184) | – |

## 2. The stopgaps

Goldens named below live in each module's `src/test/snapshots/`.

### 2.1 `DockLayout` → `CamoDockLayout`: leave

Callers: `RepositoryScreen.kt`, `PanelLayoutKeeper.kt` (feature/graph), `ChangesNavigation.kt` (the contributed Changes window), `ShellState.kt` and `MainWindow.kt` (navigation). Tests: `ComponentStateTest`, `ComponentUiTest`, `PanelLayoutKeeperTest`. Goldens: `dock-layout-light`, `dock-layout-dark`, `split-pane-focused`.

| Area | Graft | Camouflage |
|---|---|---|
| Saved sizes | dp per dock; side panels keep their width when the window resizes (ledger ruling, Task 11) | fraction of the window, 0 to 1 |
| Open windows | one per dock | several, stacked |
| Stripes | left (left and bottom windows) and right | one per edge, so a new strip along the bottom; 36 dp wide, buttons at the theme's density |
| Focus mode, `toggleSide`, `toggleSideOf` | built in | missing; `maximize` is a different thing |
| Header | title is a heading; move menu, hide | no heading semantics; "Move to" submenu, maximize, hide |
| Windows | any list | `require`s every id in the state, so the Changes window must be known when the state is created |
| Words | Graft's localizer | English defaults, replaceable through `DockStrings` |

**Saved state:** `state.json` v1 stores `panels.sizes` in dp. `CamoDockState.apply` drops any size outside 0..1, so today's files would silently fall back to the default sizes. A swap needs a new field or version, a fixture and a migration test, and therefore an ADR (Storage Formats rule 6). `PanelLayout.open` also holds one id per dock.

Cost is high (a wrapper for focus mode and the side toggles, a format change) and the result behaves less like the design.

### 2.2 `SplitPane` → `CamoSplitPane`: leave

No screen uses it directly; only `DockLayout` and tests do. `CamoSplitState` keeps fractions, not dp. It also differs by: Home/End added; Enter collapses only a `collapsible` pane; the divider takes 1 dp of layout (Graft's takes none); an 8 dp grab area (7). Revisit with the dock, or when a screen needs its own split (the merge viewer).

### 2.3 `DocumentTabs` → `CamoDocumentTabs`: leave for now

Caller: `MainWindow.kt` (repository tabs). Tests: `ComponentUiTest`, `ChromeTest`. Goldens: `tabs`, `shell-chrome-light`, `shell-chrome-dark`, `shell-chrome-home`.

- **Blocker:** "Close", "Move earlier", "Move later", "Pin", "modified", "pinned" and "Show all tabs" are hard-coded English with no theme strings. Graft ships Indonesian, so this is a regression.
- Keyboard: reorder moves from `Mod+Shift+←/→` to `Alt+←/→`.
- Order and pins move into `CamoDocumentTabsState`; Graft's `onMove` goes away.
- Look: a pinned tab shows a hollow dot instead of the pin icon; a modified, selected tab shows the dot instead of close; titles are capped at 180 dp with a tooltip; close no longer shows on keyboard focus.
- Gains: the list-all menu has speed search; screen readers get Close, Move and Pin actions.

Found on the way (not part of this plan): `MainWindow.kt` passes `onMove = { _, _ -> }`, so repository tabs can't be reordered today.

### 2.4 `CommandPalette` → `CamoCommandPalette`: leave

Only caller: the revision picker in `ComparePage.kt`. Tests: `ComponentUiTest`. Goldens: `command-palette`, `compare-picking-light`.

- Camouflage's palette owns the query (`CamoCommandPaletteState`) and runs suspend sources; Compare's query and results are view-model state.
- The placeholder is theme-wide, with no per-call parameter; Compare needs its own ("Branch, tag or commit").
- Esc clears the query first and closes on the second press.
- It sits at 1/8 of the window height (Graft: 48 dp), keeps recents, and limits a source to 8 items by default (Compare: 50).
- Row position ("2 of 9") is spoken in hard-coded English.

One caller and no gain in behavior: not worth it until Find Action needs recents or fuzzy ranking.

### 2.5 `SearchPalette`: leave

Caller: `SearchPage.kt` (navigation); goldens `search-*`. `CamoCommandPalette` has no tabs, footer, notes or per-group failure with Retry (a source that throws shows as empty), and is a popup rather than a panel. The ledger ruling that keeps it separate still holds.

### 2.6 `SpeedSearch` → Camouflage's speed search: swap in part (Task 2)

Callers: `BranchesPage.kt`, `BranchTree.kt`, `DetailsPage.kt`, `GraphPage.kt`, `ComparePage.kt`; `highlightMatches` also in `CommandPalette.kt`.

- Equivalent: Filter and Jump modes; prefix, then substring, then camel humps; Esc, Backspace and space handling.
- Changes: matching ignores accents ("zur" finds "Zürich"); hump matching backtracks, so a few more labels match; characters outside the BMP are truncated when typed (Graft handles them).
- `CamoMatcher.match(query, text)` takes the arguments **in the reverse order** of `SpeedSearchMatcher.match(label, query)`.
- **`CamoSpeedSearchOverlay` can't be used with Graft's own lists:** `matchCount` has an internal setter, so outside `CamoTree`, `CamoDataTable` and menus it always reads "No matches". Keep Graft's `SpeedSearchField`.
- Highlight color defaults to `tertiaryContainer`; set `SpeedSearchTheme` to `primaryContainer` to keep today's look.

### 2.7 `Scrollbars` → `CamoScrollbar`: swap, gated (Task 3)

Only caller: the graph list in `GraphPage.kt`. `GraftHorizontalScrollbar` has no caller.

- Look: 6 dp growing to 10 (4 to 8), square (4 dp radius), `onSurfaceVariant` (outline), hides after 1.2 s (1 s). `ScrollbarTheme` can override all of it.
- Behavior: a click on the track pages by a viewport; hidden from screen readers; draws nothing without pointer input.
- Gain: `visibility = Always` covers the macOS "show scroll bars" gap in the ledger.
- **Suspected bug, not reproduced:** `thumbGestures` uses `pointerInput(vertical, track, start, thumb, adapter)`. `start` changes while the thumb is dragged, which would restart the gesture and end the drag after the first step. Task 3 tests this first.
- Goldens: `graph-*` should not change, since an idle bar is invisible.

### 2.8 `TableColumns` → `CamoDataTable`: leave

Callers: `GraphPage.kt`, `GraphRows.kt`. Tests: `ComponentStateTest`, `ComponentUiTest`. Goldens: `table-header`, `graph-*`.

- Column resizing exists only inside `CamoDataTable`; the header cell is internal. The graph is its own `LazyColumn` (lane canvas, WIP row, stash rows, speed search, context menus, paging), which would all have to fit the table's rows.
- Keyboard: Camouflage uses `Ctrl+←/→` to resize and `Alt+←/→` to reorder; Graft uses `Alt` to resize. `Ctrl+←/→` also switches Spaces on macOS by default.
- Saved state could be converted without a format change (`columns: Map<String, Float?>`), as long as reordering stays off.

## 3. Smaller places

| Place | Where | Camouflage | Verdict |
|---|---|---|---|
| Segmented choices | `ComparePage.kt` `KindButton`, `DiffScreenPage.kt` `ModeButton` (Outline/Ghost buttons); `HistoryPage.kt` was done in PR #184 | `CamoSegmentedControl` | **Swap** (Task 4): a real selectable group with one Tab stop |
| Search tabs | `SearchPalette.kt` | `CamoTabs` / segmented | Leave: every key is handled in the search box |
| Breadcrumbs | `ComparePage.kt`, `DiffScreenPage.kt`, `WorkingDiffPage.kt` | `CamoBreadcrumb` | Leave: an item has only a visible label, so "Graph" would lose its spoken name "Back to the graph" |
| Toasts with Undo | `ChangesPage.kt`, `CommitBox.kt`, `HistoryPage.kt` | `CamoMessenger` | Leave: the same bar shows busy and error states; the messenger is one app-wide popup with no 10 s duration (UX Writing), which would need `Indefinite` plus a cancel |
| Commit Options | `CommitBox.kt` inline section | `CamoPopover` | Leave: the section works and has goldens |
| Commit description | `CommitBox.kt` `BasicTextField` | `CamoTextField(singleLine = false)` | **Swap** (Task 5) |
| Drop-down row | `ChoiceRow.kt` | `CamoSelect` | Not assessed; outside this task's list |

## 4. Ledger rulings that say Camouflage lacks something (`plans/progress.md`)

| Ruling | Says | In 0.2.0 |
|---|---|---|
| 2026-11-16 (author), density | controls fixed at 48 dp until camouflage#70 | **Out of date.** `CarbonDensity` Xs/Sm/Md/Lg exists. Graft passes no density, so with a mouse every control Graft doesn't size itself is now **Md, 40 dp** (probably why PR #174 re-recorded the home goldens) |
| G1 Task 11, split pane in dp | open question in camouflage#71 | shipped with fractions; the reason to keep dp stands |
| G1 Task 11, column reorder and fit | not built | Camouflage has both, only inside `CamoDataTable` |
| G1 Task 11, scrollbar setting | no macOS "show scroll bars" | `visibility` parameter exists (Task 3) |
| Search Everywhere, own palette | `CommandPalette` lacks tabs | still true of Camouflage's |
| G2 Task 11, checkbox | no mixed state | **still true** (`checked: Boolean`) |
| G2 Task 13, Options | "Camouflage has no popover" | **wrong:** `CamoPopover` exists; corrected in the ledger by PR #184 |
| G2 Task 13, description | "no multi-line mode" | **wrong:** `singleLine = false` exists |

Menu submenus (camouflage#77) also shipped (`CamoMenuSubmenu`); Graft has no stopgap for them.

## 5. For the author to decide

1. **Wrappers.** ADR-22 says `:module:core:ui` wraps Camouflage behind `Graft*` names. Features import `Camo*` directly, which UI Design ("Components"), the 2026-11 ledger ruling and `checkImports` all follow, and ADR-24 removed `ui-kit`. Neither ADR-22 nor ADR-24 says that part is superseded. Recommendation: keep direct imports and mark it in ADR-22's heading.
2. **Density** (Task 1): **decided 2026-11-26: `Sm`** (over `Xs`). `Sm` is 32 dp, matching `formControl` and `menuItem`; toolbar controls keep their 24 dp modifier. `Xs` makes 24 dp the default but draws control text at 12 sp.
3. **Camouflage issues.** File these upstream? Nothing is filed without your say-so.
   - Document tabs: translatable strings.
   - Dock: sizes that can stay in dp; one open window per edge; heading semantics; focus mode.
   - Speed search: public `matchCount` (or a count parameter on the overlay).
   - Command palette: per-instance placeholder, translatable position text.
   - Breadcrumb: a spoken label per item.
   - Scrollbar: the drag bug, if Task 3 confirms it.
4. **Note edits** (yours, then synced): ADR-22's density sentence and UI Design's list of what Graft adds are out of date after these tasks.

## 6. Tasks

For every task: run GitNexus impact before editing, write the test first where it applies, finish with `./gradlew check`, update `plans/progress.md`, and stop for review. Goldens are compared and recorded on Linux only: a visual change fails `check` on the Ubuntu job, and the new images come from that run's upload (or `-PupdateGoldens` on Linux).

### Task 1: Set Carbon's density

- **Files:** `module/core/ui/.../theme/GraftTheme.kt`, `GraftDensity.kt` (KDoc); then drop size modifiers only where they now repeat the theme.
- **Tests:** a theme test that the density token is the chosen one; the existing theme validator test; keyboard tests unchanged.
- **Goldens:** possibly any with a button, checkbox, field or menu that Graft doesn't size; the CI run says which.
- **Risk:** medium (wide visual change, no behavior change). Check dense rows don't grow, as `RowCheckbox` in `DiffViewer.kt` guards against.
- **Done when:** unsized controls draw at `Sm` (32 dp) and the re-recorded goldens are reviewed by eye.

### Task 2: Speed search on Camouflage's matcher and state

- **Files:** `SpeedSearch.kt` (remove `SpeedSearchMatcher`, `SpeedSearchState`, `highlightMatches`, `FilteredNode`, `filterTree`; the last two have no caller but a test; keep `SpeedSearchField`), `BranchesPage.kt`, `BranchTree.kt`, `DetailsPage.kt`, `GraphPage.kt`, `ComparePage.kt`, `CommandPalette.kt`, `GraftTheme.kt`.
- **Tests:** keep the matcher cases in `ComponentStateTest`, now against `CamoMatcher`, and add an accented label; the speed search keyboard tests in `ComponentUiTest` pass unchanged (including arrows and Enter staying with the list).
- **Goldens:** `speed-search`; others only if the highlight differs.
- **Risk:** low to medium (argument order; jump mode in the graph and details).
- **Done when:** no matcher of Graft's own remains and filter and jump behave as before.

### Task 3: Camouflage's scrollbar

- **Files:** `GraphPage.kt`, delete `Scrollbars.kt`, `GraftTheme.kt` (`ScrollbarTheme` if 4/8 dp is kept).
- **Tests first:** drag the thumb a long way over a long `LazyColumn` in one gesture and assert it keeps scrolling. **If it fails, stop:** keep `Scrollbars.kt` and report upstream.
- **Goldens:** none expected.
- **Risk:** low. Lazy content length is an estimate, so try a large repository by hand.
- **Done when:** the drag test passes and the graph scrolls by wheel, thumb and track.

### Task 4: Segmented controls

- **Files:** `ComparePage.kt`, `DiffScreenPage.kt`. Follow `HistoryPage.kt` (PR #184): `CamoSegmentedControl` with `Modifier.height(density.control)`.
- **Tests:** their UI tests: each choice selects, reports selected, arrows move within the group.
- **Goldens:** `compare-*`, `diff-screen-*`.
- **Risk:** low; Tab order changes (one stop per group). Do after Task 1.
- **Done when:** both hand-built groups are gone.

### Task 5: Commit description as a `CamoTextField`

- **Files:** `CommitBox.kt`.
- **Tests:** typing several lines, Enter inserts a newline, the commit shortcut still commits, the box keeps its height with a long message, the spoken name is unchanged.
- **Goldens:** `commit-*`.
- **Risk:** medium: Carbon's field style takes its height from the density token, so check it grows. If it can't, keep the `BasicTextField` and record why.
- **Done when:** swapped, or the ruling is rewritten with the real reason.

### Task 6: Correct the record

- **Files:** `plans/progress.md` (new lines replacing the out-of-date rulings in section 4, except the popover one, already done; old lines stay), KDoc that names camouflage#70–#78 as pending in `GraftDensity.kt` and the components that stay.
- **Tests:** none beyond `./gradlew check`.
- **Risk:** none.
- **Done when:** no ledger line or KDoc claims Camouflage lacks something 0.2.0 has.

**Order:** 1, then 2 to 5 in any order, then 6. Whether this runs before or after the rest of G2 is the author's call.
