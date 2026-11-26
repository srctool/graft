# Switch Graft's look to Camouflage's Fluent skin

**Status: approved 2026-11-26 with the recommended choices, and Inter as the UI font. Task 1 is built and waits for review. ADR-29 and the note edits are in the notes and synced (2026-11-26).**

**Goal:** draw Graft with `camouflage-skin-fluent` 0.2.0 instead of Carbon, with Inter as the UI font, keeping Graft's layout, behavior, Atom One colors and icons. The author prefers Fluent after seeing both, and chose Inter (2026-11-26).

**Source notes:** ADR-22 (what this replaces), ADR-24; UI Design; Accessibility; Libraries. Camouflage's Fluent Skin note and its 0.2.0 sources jar.

**This reverses an accepted ADR**, so no code starts before ADR-29 (section 2) is accepted.

## 1. What a trial showed

A throwaway branch swapped only the skin and theme, and re-rendered the screenshots on macOS.

- Three files changed: the version catalog, `:module:core:ui`'s build file, `GraftTheme.kt`.
- In `core:ui`, `navigation`, the three features and `app:desktop`, one test failed: the one asserting Carbon's square shapes.
- Dialogs and fields get rounded corners; secondary and ghost buttons turn neutral. The graph, diff lines and document tabs barely change.
- **The buttons did not use Atom One's blue.** Fluent's renderers take button fills, hover steps, strokes, focus and shadows from `FluentTokens`, not from the theme's color roles, and the trial left Fluent's own. (The trial report said the colors were unchanged; that was wrong for buttons and these state colors.)
- Button text looked small: the trial used `FluentDensity.Small` (24 dp, 12 sp).
- Ghost buttons used as navigation ("New workspace", "Scan a folder…") read as plain text.
- The "Name <email>" label in the commit options is larger than the labels around it.
- **Field and button heights don't match** (found by the author). Measured in the clone dialog's renders:

  | | Field box | "Browse…" beside it | Bottom edges |
  |---|---|---|---|
  | Carbon (today) | 40 | 32 | 8 px apart |
  | Fluent (trial) | 34 | 32 | 4 px apart |

  So the mismatch is already in the app and Fluent only shrinks it. Causes, from the code:
  - A text field takes the skin's own height; Fluent's is 32 whatever the density (34 measured; why is not yet known).
  - Buttons beside a field are sized by hand, `formControl` (32) in some places and `control` (24) in others, and some fields and search fields are forced to 24.
  - Buttons are pushed under a field's label with a fixed `padding(top = 20.dp)` (`ClonePage.kt`, `InitPage.kt`), which assumes one label height.

Not tried: Inter, the running app, hover and focus states, Acrylic, Mica, Windows, Linux.

## 2. Proposed ADR-29 (yours to add to the notes, then synced)

> ### ADR-29 Fluent look with Atom One colors, through Camouflage (supersedes ADR-22's visual style)
> - **Context:** G1 and most of G2 are built on Camouflage's Carbon skin. Seen side by side on Graft's screens, the author prefers Camouflage's Fluent skin.
> - **Decision:**
>   - **Structure:** Fluent 2 through `camouflage-skin-fluent`, `Solid` surfaces: 4 px control corners, 8 px dialogs and flyouts, shadows on overlays, subtle hover and press steps.
>   - **Colors:** unchanged, Atom One. Fluent's state tokens are built from the same palette, so no Fluent blue or gray shows.
>   - **Type:** **Inter** (OFL-1.1, bundled), the UI font of JetBrains IDEs, replaces IBM Plex Sans. JetBrains Mono stays for code and diffs.
>   - **Icons:** unchanged, Carbon's icon set.
>   - **Density:** `FluentDensity.Medium` (32 dp); toolbars, panels and rows keep their 24 dp sizes.
>   - **Unchanged from ADR-22:** screens use Camouflage, Material 3 isn't a dependency, git widgets stay Graft's.
> - **Consequences:** every screenshot golden is re-recorded. Acrylic and Mica wait. camouflage#70 (Carbon density) no longer matters to Graft.

Other note edits that follow: UI Design (look, the "Carbon at Graft's density" table, tabs, ref labels), Accessibility (the focus line), Overview, Architecture (UI row and `:module:core:ui` row), Libraries (`camouflage-skin-fluent`, Inter in place of IBM Plex Sans). ADR-27 and ADR-28 are accepted but not in the notes yet, hence the number 29.

## 3. For the author to decide

| Question | Recommendation | Why |
|---|---|---|
| Colors | Atom One, including Fluent's state tokens | You chose Atom One for the graph and syntax too; Fluent's blue beside it would show two accents |
| Density | `Medium` | 32 dp matches `formControl`, `menuItem` and Fluent's fields, which are always 32; button text stays 14 sp |
| Font | **decided 2026-11-26: Inter** (replaces IBM Plex Sans) | JetBrains IDEs' UI font; bundled, so the same on every OS and in goldens. JetBrains Mono stays for code |
| Icons | keep Carbon's | Fluent UI System Icons would be a separate, larger change |
| Focus ring in Graft's own components | match Fluent's double stroke | one focus style across the app |
| Acrylic, Mica | later | Mica is Windows-only, needs window setup, and Camouflage hasn't checked it on Windows |
| Order | before G2 Task 15 | new screens get designed once |

## 4. Tasks

Each is test first where it applies, runs GitNexus impact before editing, ends with `./gradlew check`, updates `plans/progress.md`, and stops for review. Goldens are recorded on Linux (CI's upload) and looked at before they're committed. `graftTheme` has a **CRITICAL** impact (38 symbols, 146 flows): it draws every screen.

### Task 1: the skin and the theme

- **Files:** `gradle/libs.versions.toml`, `module/core/ui/build.gradle.kts`, `theme/GraftTheme.kt`, `theme/GraftAssets.kt` and the font resources (Inter in, IBM Plex Sans out, with their OFL texts), the About page's licence list, new `theme/GraftFluentTokens.kt`, `GraftThemeTest.kt`, the "Look" row in `AGENTS.md` and `:module:core:ui` row in `ARCHITECTURE.md`.
- **Tests first:** shapes are Fluent's and the UI font is Inter, with its licence file shipped; in light, dark and high contrast every `FluentTokens` color comes from Graft's palette; contrast of text on the primary button and its hover and press is at least 4.5:1; focus stroke and control stroke at least 3:1 on the window. Camouflage's validator checks color roles only, not these tokens.
- **Inter's files:** downloaded from its official release only after you approve the file names, source and size. The dense 13 sp step's letter spacing was Carbon's for Plex and is rechecked.
- **Done when:** no Carbon dependency remains and the re-recorded goldens show Atom One's blue on primary buttons.

### Task 2: Graft's own components

- **Files:** `GraftDensity.kt` (drop size workarounds the theme now covers), `DocumentTabs.kt`, `SplitPane.kt`, `DockLayout.kt`, `DiffViewer.kt` (focus and the row checkbox), `RefChip.kt`, KDoc that says Carbon.
- **Tests:** keyboard and component tests pass unchanged; diff rows keep one code line's height with a checkbox.
- **Done when:** corners and focus in these match the skin, and no comment describes them as Carbon.

### Task 3: one height per row

- **Rule:** controls on one line have the same height and the same top and bottom: 32 dp in dialogs and forms (`formControl`), 24 dp in toolbars and panels (`control`).
- **Files:** every row that mixes a field with a button, select or segmented control: `ClonePage.kt`, `InitPage.kt`, `HistoryPage.kt` (search row), `SettingsPage.kt`, `CommitBox.kt`, and the toolbars with a search field. Shared sizing goes in `:module:core:ui`; the `padding(top = 20.dp)` offsets go.
- **Tests first:** for each such row, a UI test that the controls' bounds have equal top and bottom, also while the field shows its error text.
- **Risk:** medium: a field with a label above and an error below can't be aligned by its outer bounds, so it may need the label drawn outside the row. If Camouflage's field can't take a 24 dp height cleanly, record it and raise it upstream (only with the author's yes).
- **Done when:** the height tests pass and no fixed label offset remains.

### Task 4: what the new look exposes

- **Files:** the screens with navigation actions drawn as ghost buttons (Home's sidebar first), `CommitBox.kt` (the co-author label), and whatever the review of Task 1's goldens turns up.
- **Tests:** their UI tests; actions keep their names and keys.
- **Done when:** you've run the app and every list item from the review is fixed or declined.

### Task 5: the Camouflage swap plan

- **Files:** `plans/camouflage-swap.md`, `plans/progress.md`.
- Its Task 1 (Carbon density) is dropped. Its Tasks 2 to 6 were read against Carbon's sources: re-check the segmented control, text field and scrollbar against Fluent before they start.
- **Done when:** the plan says nothing that depends on Carbon.

## 5. Risks

- **Contrast.** Fluent's renderers may pair tokens differently from Carbon's; a pair no test lists could fail AA. Task 1 reads the renderers for the pairs before writing the test.
- **State colors are Graft's to keep.** A Camouflage update that adds a `FluentTokens` field breaks the build until Graft chooses its color; that is the intent.
- **Work in flight.** Any open branch with UI changes has to re-record its goldens after Task 1.
