# Draft: ADR-22 and the changes it needs

Status: **accepted (2026-11-11) and written into the notes.** The ADR text below is the draft; the accepted wording, with the Atom One colors, is ADR-22 in `docs/design/03 - Project/02 - Decisions.md`.

---

## The ADR (text for `02 - Decisions.md`)

### ADR-22 Carbon look through Camouflage; `ui-kit` wraps Camouflage now (Proposed 2026-11-11; supersedes ADR-21, amends ADR-20's Material 3 base, adopts ADR-9's `ui-kit` seam early)

- **Context:**
  - ADR-21 chose Fluent 2 drawn on Material 3, so a later switch to Camouflage's Fluent skin would change nothing visible. ADR-9 deferred Camouflage until it shipped a desktop skin.
  - Camouflage 0.1.0 is on Maven Central (Apache-2.0) with JVM builds of the core and all five skins, Carbon included. It's built on Compose 1.12.1 and Kotlin 2.4.20; Graft is on 1.12.2 and 2.4.21.
  - IBM Carbon suits Graft better than Fluent: Carbon is made for enterprise, data-heavy desktop tools. It has a strong grid, flat layered surfaces with no shadows, square corners, and inset focus borders that don't move the layout. A git client is a dense data tool: tables, trees, diffs and the graph.
  - `ui-kit` has no code yet (G0 is at Task 5), so switching now costs nothing. After G1 it would mean rewriting every component.
- **Decision:**
  - **Look:** IBM Carbon's structure through Camouflage's `carbonSkin()`: square corners, layered surfaces, inset focus, Carbon's components and type. **Colors come from Atom's One scheme**: Graft Light uses One Light, Graft Dark uses One Dark (MIT, Atom). The skin's colors are replaced through the theme's color roles, not Carbon's families.
  - **One palette, AA where it carries text:** One's colors are used as published wherever they pass WCAG 2.2 AA. Where they don't, a darker (light theme) or lighter (dark theme) step of the same hue is used, and only where needed: text, links, status text and the primary button. Camouflage's theme validator pins the result. The same palette drives diff syntax highlighting and the graph's lane colors, so the editor-familiar One look runs through the whole app.
  - **Implementation:** `ui-kit` wraps Camouflage components (`CamoButton`, `CamoTree`, `CamoDataTable`, `CamoMenu`, …) behind Graft's own `Graft*` names, and sets up the Camouflage provider with the Carbon skin and Graft's theme. Material 3 is no longer a dependency. Camouflage draws on Compose foundation.
  - **Density:** Graft keeps its desktop sizes and uses Carbon's own smaller sizes: **xs 24 px** in toolbars, panels and inline, **sm 32 px** in dialogs and forms. Rows are 24 px in panels and trees. Camouflage's Carbon skin builds only the 48 px sizes (40 px "productive" isn't built either). The sizes are set through Camouflage's component themes in `ui-kit`. If the themes can't express them, Graft asks Camouflage for a Carbon density option, as Fluent has (see the open questions).
  - **Unchanged:** ADR-20's GitKraken layout and JetBrains behaviors. Graft's own widgets stay Graft's: the graph canvas, diff and merge viewers, split panes, tool windows, speed search and ref chips. They're drawn with Camouflage tokens.
  - **Timing:** ADR-9 says to adopt between milestones. This is an accepted exception: G0 has no UI code, and the G0 spike (Task 8) is throwaway code that doesn't use `ui-kit`.
- **Consequences:**
  - The CI rule changes: only `ui-kit` may import `com.srctool.camouflage`, and nothing imports `androidx.compose.material3`.
  - Graft depends on a 0.x library whose API can still change. Pin it in the version catalog; Graft's needs feed back into Camouflage as its first real desktop app (as ADR-21 expected for Fluent).
  - The UI font is **IBM Plex Sans**, bundled (OFL-1.1). Skins never bundle fonts, so the app does. This replaces the platform-font rule.
  - The Fluent-only items are dropped: Mica and Acrylic, the double-stroke focus, the Fluent type ramp and Fluent shadows.
  - Carbon's dark header (gray 100 in every theme) is replaced by One's panel color, so Graft Light's toolbar is light.
  - Light-theme accents need darker steps for text, so Graft's light theme isn't exactly One Light where text needs AA.

---

## Questions (all answered 2026-11-11)

| # | Question | Decision |
|---|---|---|
| Q1 | **Icons:** keep Fluent UI System Icons (MIT), or switch to Carbon's icon set (`@carbon/icons`, Apache-2.0)? | **Decided (2026-11-11): Carbon icons**, after checking the set covers branch, merge, commit, pull request, tag and fork; missing git icons drawn in Carbon's 16/20/32 grid |
| Q2 | **Code and diff font:** JetBrains Mono, or IBM Plex Mono to match Plex Sans? | **Decided (2026-11-11): keep JetBrains Mono**; IBM Plex Mono as an option in Settings |
| Q3 | **Light and dark colors** | **Decided (2026-11-11): Atom One.** Graft Light = One Light, Graft Dark = One Dark, on Carbon's structure. See "Atom One on Carbon" below |
| Q4 | **Density in Camouflage** ([srctool/camouflage#70](https://github.com/srctool/camouflage/issues/70)): set 24/32 px through Graft's component themes, or add a Carbon density option (xs/sm/md/lg) upstream in Camouflage? | **Decided (2026-11-11): upstream** ([camouflage#70](https://github.com/srctool/camouflage/issues/70)); until it ships, `ui-kit` overrides the component themes, after checking the heights can be overridden from outside the skin |
| Q5 | **Brand color** | **Decided (2026-11-11): One's blue** (`#61afef` dark, `#4078f2` light, AA-adjusted where it carries text). Follows from Q3 |
| Q6 | **Sinew:** Sinew 1.0 is out too. Adopt its seams (`GraftError` → `SinewException`, `GraftViewModel`, `core-network`) in the same move? | **Decided (2026-11-11): separate ADR** for the Sinew seams, decided before Task 5 resumes |

---

## Atom One on Carbon (Q3, Q5)

Published values from Atom's `one-dark-ui` / `one-dark-syntax` and `one-light-ui` / `one-light-syntax` (MIT). Contrast is WCAG 2.2 against the editor background, computed for this draft.

| Role | One Dark | Contrast | One Light | Contrast |
|---|---|---|---|---|
| Window background (`surface`) | `#282c34` | | `#fafafa` | |
| Panels, sidebars (`surfaceContainerLow`) | `#21252b` | | `#eaeaeb` | |
| Raised layer (`surfaceContainerHigh`) | `#2c313a` | | `#f0f0f1` | |
| Selection | `#3e4451` | | `#e5e5e6` | |
| Text (`onSurface`) | `#abb2bf` | 6.6 ✓ | `#383a42` | 10.9 ✓ |
| Secondary text (`onSurfaceVariant`) | `#5c6370` (comment) | **2.3 ✗** | `#a0a1a7` | **2.5 ✗** |
| Accent, links, primary (`primary`) | `#61afef` | 5.9 ✓ | `#4078f2` | **3.9 ✗** |
| Text on the primary button | `#282c34` on blue | 5.9 ✓ (white fails: 2.4) | white on blue | **4.1 ✗** |
| Error (`red`) | `#e06c75` | **4.4 ✗** (just below) | `#e45649` | **3.5 ✗** |
| Success (`green`) | `#98c379` | 6.9 ✓ | `#50a14f` | **3.1 ✗** |
| Warning (`yellow` / `orange`) | `#e5c07b` | 8.1 ✓ | `#c18401` / `#986801` | 3.1 ✗ / 4.7 ✓ |
| Info (`cyan`) | `#56b6c2` | 5.9 ✓ | `#0184bc` | **4.0 ✗** |
| Purple (lanes, syntax) | `#c678dd` | 4.8 ✓ | `#a626a4` | 5.9 ✓ |

What that means:
- **One Dark** works almost as published. Only the comment gray needs a lighter secondary-text step (about `#9198a4`, ≈ 4.7 on the background), red needs a slightly lighter step for error text, and the primary button uses dark text on blue, not white.
- **One Light** needs darker steps of blue, red, green, yellow and cyan wherever they're text, plus a darker secondary gray. As fills and graph lines (3:1 for non-text) most of them pass. The exact steps are picked in G1 with Camouflage's theme validator.
- **Lanes:** the graph's 8-color palette is One's blue, purple, green, red, orange, yellow, cyan and the text gray, always paired with labels and marker shapes ([Accessibility](../docs/design/01%20-%20Foundations/05%20-%20Accessibility.md)), since red and green alone don't separate for color-blind users.
- **Syntax highlighting** in diffs uses One's syntax colors, so code in Graft looks the way it does in Atom-family editor themes (One Dark is the default in many).
- **High contrast** stays a third theme that follows the OS high-contrast colors.
- **Carbon's dark header** (gray 100 in every theme) becomes One's panel color, so the title-bar toolbar matches the rest of the theme.
- **Credit:** the One color values are credited in `NOTICE` and the Libraries note (Atom, MIT).

## Components proposed to Camouflage

Components Graft needs that Camouflage doesn't have yet, opened in `srctool/camouflage` (Backlog on the Camouflage project) so every desktop app gets them. Until one ships, Graft builds it in `ui-kit` on Camouflage tokens, shaped like the proposal, so it can be swapped later.

| Issue | Component |
|---|---|
| [#70](https://github.com/srctool/camouflage/issues/70) | Carbon skin desktop density (xs 24, sm 32, md 40, lg 48) |
| [#71](https://github.com/srctool/camouflage/issues/71) | `CamoSplitPane` with a draggable divider |
| [#72](https://github.com/srctool/camouflage/issues/72) | `CamoDataTable`: resizable, hideable, reorderable columns |
| [#73](https://github.com/srctool/camouflage/issues/73) | Speed search in trees, lists and menus |
| [#74](https://github.com/srctool/camouflage/issues/74) | Document tabs: closable, reorderable, overflowing |
| [#75](https://github.com/srctool/camouflage/issues/75) | Dock layout: tool windows with edge stripes |
| [#76](https://github.com/srctool/camouflage/issues/76) | `CamoCommandPalette` (Find Action, Search Everywhere) |
| [#77](https://github.com/srctool/camouflage/issues/77) | `CamoMenu` submenus |
| [#78](https://github.com/srctool/camouflage/issues/78) | Desktop overlay scrollbars |

Git-specific widgets (graph canvas, diff and merge viewers, ref chips) stay in Graft.

## Changes to the design notes

Paths are relative to the notes folder (`projects/graft/`). "→" means replace with.

| Note | Section | Change |
|---|---|---|
| `03 - Project/02 - Decisions.md` | ADR-21 heading | add "(superseded by ADR-22)" |
| | ADR-20 heading | "(visual style superseded by ADR-21; Material 3 base superseded by ADR-22)" |
| | ADR-9 | no edit (ADRs aren't edited); ADR-22 records the early `ui-kit` adoption |
| | new | ADR-22, text above |
| `01 - Foundations/06 - UI Design.md` | opening lines | "Microsoft's Fluent 2 look" → "IBM Carbon's look"; the Look bullet → Carbon at desktop density (24/32 px, square corners, layered surfaces, inset focus) through Camouflage's Carbon skin (ADR-22) |
| | "Toolkit: Material 3 with a Fluent theme" | → "Toolkit: Camouflage with the Carbon skin". Replace "Why Fluent" with why Carbon (the ADR's context). Keep the Jewel paragraph |
| | "What the Graft theme changes" table | → "Carbon at Graft's density": buttons xs 24 / sm 32, square (tags and toggles stay pills); text fields 24/32 on the `field` fill with a bottom border, 2 px inset focus; rows 24 (28 comfortable); menu items 32; type from Carbon's productive sets in IBM Plex Sans (body-compact-01 14, label-01 12, heading-02 16, heading-03 20); 13 px Graft step for dense panels and the graph (keep); no shadows except overlays; layers step one gray per nesting level; hover/active background tokens instead of Fluent's subtle feedback; Carbon blue 60 as the interactive color; line tabs; menus with the single overlay shadow, no Acrylic; scrollbars (keep) |
| | "What Material doesn't have" | → "What Graft builds on top of Camouflage": split pane, tool windows with edge stripes, speed search in trees and popups, compact closable editor tabs, the git widgets. Camouflage covers trees (`CamoTree`), tables (`CamoDataTable`), menus, popovers, side panels |
| | Title bar | drop the Mica row; add that Carbon's header is dark (gray 100) in every theme |
| | Assets | UI font → IBM Plex Sans (OFL-1.1, bundled on every OS); code font per Q2; icons per Q1; themes → Graft Light (One Light colors), Graft Dark (One Dark colors), High contrast; syntax highlighting from One's syntax colors |
| | `ui-kit` components | "Most wrap a Material 3 component styled with the Fluent tokens" → "Most wrap a Camouflage component rendered by the Carbon skin". Table unchanged |
| | Graph style | ref chips: "small rounded chips" → Carbon tags (pills, the one rounded element); lane palette = One's 8 colors, checked against both themes |
| `00 - Overview.md` | diagram node, stack line, doc map line | "Material 3 with a Fluent 2 theme" → "Camouflage with the Carbon skin" |
| | Open questions | "Stack: … without Sinew and Camouflage for now" → add "Camouflage adopted for `ui-kit` (ADR-22); Sinew per Q6" |
| `01 - Foundations/02 - Architecture.md` | Stack table, UI row | → "Compose Multiplatform Desktop + Camouflage (Carbon skin), always used through Graft's own `ui-kit`" |
| | Rules | "Features never import `androidx.compose.material3` directly; only `ui-kit` does" → "only `ui-kit` imports Camouflage (`com.srctool.camouflage`); nothing imports `androidx.compose.material3`" |
| `04 - Development/07 - Libraries.md` | UI row | → Compose Multiplatform (desktop) + Camouflage (`camouflage-bom`, `camouflage-core`, `camouflage-skin-carbon`), Apache-2.0, only through `ui-kit`; pin the version; Graft's Compose and Kotlin at or above Camouflage's |
| | Not used | add Material 3 (ADR-22) |
| | Assets | add IBM Plex Sans (OFL-1.1); Atom One color values (MIT, credited); icons per Q1; keep Inter only if it's still needed for something (it isn't, once Plex is bundled) |
| `04 - Development/02 - Conventions.md` | UI row | "Fluent tokens from the theme" → "Camouflage theme tokens" |
| `04 - Development/01 - Project Setup.md` | §3.4 `checkImports` | → fails when a file outside `ui-kit` imports `com.srctool.camouflage`, when any file imports `androidx.compose.material3`, or when a file in `core-*` / `app-cli` imports `androidx.compose` |
| `01 - Foundations/05 - Accessibility.md` | General | add: themes use Atom One colors, with darker or lighter steps where text needs AA; focus is Carbon's 2 px inset border |

## Changes in this repository (after the notes are synced)

| File | Change |
|---|---|
| `AGENTS.md` §2 | the Material rule → "only `ui-kit` imports Camouflage; nothing imports `androidx.compose.material3`" (ADR-9, ADR-22); the Look row → "IBM Carbon via Camouflage's Carbon skin at desktop density (24/32 px, square corners, layered surfaces)" (UI Design, ADR-22); the Sinew/Camouflage row → "Camouflage is the `ui-kit` toolkit (ADR-22); Sinew isn't a dependency yet; keep the `GraftViewModel`, `GraftError` and `core-network` seams" |
| `ARCHITECTURE.md` | `ui-kit` row → "Graft's components on Camouflage (Carbon skin), the theme, `GraftViewModel`"; invariant 7 → "Only `ui-kit` imports Camouflage" |
| `CONTRIBUTING.md` | "only `ui-kit` imports Material 3" → "only `ui-kit` imports Camouflage" |
| `gradle/libs.versions.toml` | add `camouflage = "0.1.0"`, `camouflage-bom`, `camouflage-core`, `camouflage-skin-carbon` |
| `build-logic` checks (`Rules.kt`, its tests) | the new `checkImports` rule above |
| `ui/kit/build.gradle.kts` | depends on Camouflage (in G1, when `ui-kit` gets code) |
| `plans/G0.md` | Milestone *Done when*: "a Material import outside `ui-kit`" → "a Camouflage import outside `ui-kit`, or any Material 3 import"; add a small task for the `checkImports` change (Task 2 is already merged) |
| `plans/progress.md` | a line recording ADR-22 and the rulings that follow from Q1–Q6 |
| GitHub issue #11 (G1: ui-kit) | rewrite the scope: Camouflage provider + Carbon skin + Graft theme (One Light / One Dark on Carbon, Plex Sans, 24/32 density), `Graft*` wrappers over Camouflage components, screenshot tests |
| GitHub issue #1 (G0) | acceptance criterion about the Material import → the new rule |
| `README.md` | no change (it already names Camouflage as a sibling project) |

## Order of work, once accepted

1. You answer Q1–Q6; ADR-22 and the note changes go into the notes; `docs/design/` is synced.
2. The repo files above change in one commit (`docs: adopt Camouflage's Carbon skin (ADR-22)`), plus the `checkImports` change with its tests (`build(checks): …`).
3. Task 5 resumes (with Sinew's `GraftError` decision from Q6 applied first).
4. G1's `ui-kit` task builds on Camouflage.
